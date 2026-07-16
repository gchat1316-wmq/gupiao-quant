package com.quant.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.config.AiProperties;
import com.quant.config.StockAnalysisProperties;
import com.quant.dto.invest.ProsperityPickRecentDTO;
import com.quant.dto.invest.ProsperityPickResultDTO;
import com.quant.entity.InvestProsperityPick;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestProsperityPickRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;
import com.quant.service.prosperitystrong.ProsperityPickAiPromptBuilder;
import com.quant.service.prosperitystrong.ProsperityPickBaostockLoader;
import com.quant.service.prosperitystrong.ProsperityPickInfographicPromptBuilder;
import com.quant.service.prosperitystrong.ProsperityPickProfileBuilder;
import com.quant.service.prosperitystrong.ProsperityPickReportRenderer;
import com.quant.service.prosperitystrong.ProsperityPickResultAnalyzer;
import com.quant.service.search.WebSearchClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin facade over the per-stock 景气度选股 pipeline. The orchestration lives here; pure data assembly /
 * analysis / rendering lives in dedicated helpers under {@code service/prosperitystrong/}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProsperityPickService {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int RECENT_HISTORY_DAYS = 3;

  // 必须保持字段顺序与构造顺序（@RequiredArgsConstructor 按声明顺序生成构造器）
  private final StockQueryService stockQueryService;
  private final TradeStockFinancialRepository financialRepo;
  private final AStockDataQuoteService aStockDataQuoteService;
  private final InvestProsperityPickRepository repo;
  private final MiniMaxClient miniMaxClient;
  private final SenseNovaClient senseNovaClient;
  private final WebSearchClient webSearchClient;
  private final AiProperties aiProperties;
  private final StockAnalysisProperties stockAnalysisProperties;

  // 新增的 stage helpers
  private final ProsperityPickProfileBuilder profileBuilder;
  private final ProsperityPickBaostockLoader baostockLoader;
  private final ProsperityPickAiPromptBuilder aiPromptBuilder;
  private final ProsperityPickResultAnalyzer resultAnalyzer;
  private final ProsperityPickReportRenderer reportRenderer;
  private final ProsperityPickInfographicPromptBuilder infographicPromptBuilder;

  @Transactional
  public ProsperityPickResultDTO analyze(String keyword, boolean force) {
    if (keyword == null || keyword.isBlank()) {
      throw new IllegalArgumentException("请输入股票名称或代码");
    }
    TradeStockBasic basic =
        stockQueryService
            .resolveStock(keyword.trim())
            .orElseThrow(() -> new IllegalArgumentException("未找到股票: " + keyword));

    LocalDate today = LocalDate.now();
    if (!force) {
      Optional<InvestProsperityPick> cached =
          repo.findByStockCodeAndAnalysisDate(basic.getStockCode(), today);
      if (cached.isPresent()) {
        InvestProsperityPick cachedEntity = cached.get();
        if (cachedEntity.getDegraded() == null || cachedEntity.getDegraded() != 1) {
          log.info("命中缓存: {} {}", basic.getStockCode(), today);
          return resultAnalyzer.toResultDTO(cachedEntity, basic, true);
        }
        log.info("命中演示数据缓存，重新分析: {} {}", basic.getStockCode(), today);
      }
    }

    long startMs = System.currentTimeMillis();

    // ① 构建基础 Profile
    ProsperityPickResultDTO.Profile profile = profileBuilder.buildProfile(basic);

    // ② 抓取 baostock 真实数据（可选，失败不阻断主流程）
    Map<String, Object> baostockData;
    ProsperityPickResultDTO.FinancialSummary financialSummary = null;
    try {
      baostockData = baostockLoader.fetchBaostockPack(basic.getStockCode());
      if (baostockData != null && !baostockData.isEmpty()) {
        financialSummary = resultAnalyzer.buildFinancialSummaryFromBaostock(baostockData);
        log.info("baostock 数据获取成功: {}", basic.getStockCode());
      }
    } catch (Exception e) {
      log.warn("baostock 数据获取失败，继续 AI 分析: {}", e.getMessage());
      baostockData = null;
    }

    // ③ 紫苏叶 + 九维框架分析（基于 baostock 数据）
    ProsperityPickResultAnalyzer.PurplePerillaOutcome outcome =
        resultAnalyzer.runPurplePerillaStage(baostockData, basic, profile.getCurrentPrice());

    // ④ AI 六维深度分析（行业/公司/估值/技术/资金/总结）
    String prompt = aiPromptBuilder.buildPrompt(profile, basic, baostockData);
    String aiJson;
    try {
      aiJson = analyzeWithAi(prompt);
    } catch (Exception e) {
      log.warn("AI 调用失败: {}", e.getMessage());
      throw new IllegalStateException("AI 调用失败: " + e.getMessage(), e);
    }

    int elapsedMs = (int) (System.currentTimeMillis() - startMs);

    // ⑤ 保存
    InvestProsperityPick entity =
        resultAnalyzer.buildEntity(
            basic, today, aiJson, outcome, baostockData, elapsedMs, force, repo);
    InvestProsperityPick saved = repo.save(entity);
    log.info("景气度选股分析完成: {} elapsed={}ms", basic.getStockCode(), elapsedMs);

    // ⑥ 生成报告详情 HTML（异步不阻塞返回）
    try {
      String reportHtml = reportRenderer.buildReportHtml(saved, basic);
      saved.setReportHtml(reportHtml);
      repo.save(saved);
    } catch (Exception e) {
      log.warn("报告详情 HTML 生成失败，不影响主流程: {}", e.getMessage());
    }

    return resultAnalyzer.toResultDTO(saved, basic, false).toBuilder()
        .chainPosition(outcome.chainPosition())
        .nineDimension(outcome.nineDimension())
        .financialSummary(financialSummary)
        .moatScore(outcome.moatScore())
        .verdict(outcome.verdict())
        .catalysts(outcome.catalysts())
        .risks(outcome.risks())
        .elapsedMs(elapsedMs)
        .reportHtml(saved.getReportHtml())
        .build();
  }

  // ================================================================
  // AI 分析（六维研报）— MiniMax 优先，失败回退 SenseNova
  // ================================================================

  private String analyzeWithAi(String prompt) {
    Exception miniMaxError = null;
    try {
      return normalizeAiJson(miniMaxClient.chatComplete(SYSTEM_PROMPT, prompt));
    } catch (Exception e) {
      miniMaxError = e;
      log.warn("MiniMax 分析失败，尝试 SenseNova: {}", e.getMessage());
    }

    try {
      return normalizeAiJson(senseNovaClient.chatComplete(SYSTEM_PROMPT, prompt));
    } catch (Exception senseNovaError) {
      String message =
          "MiniMax: " + miniMaxError.getMessage() + "; SenseNova: " + senseNovaError.getMessage();
      throw new IllegalStateException(message, senseNovaError);
    }
  }

  private String normalizeAiJson(String raw) {
    String aiJson = aiPromptBuilder.extractJson(raw);
    try {
      MAPPER.readTree(aiJson);
    } catch (Exception e) {
      throw new IllegalStateException("AI 返回不是合法 JSON: " + e.getMessage(), e);
    }
    return aiJson;
  }

  // ================================================================
  // 信息图
  // ================================================================

  @Transactional
  public String generateInfographic(Long id) {
    InvestProsperityPick entity =
        repo.findById(id).orElseThrow(() -> new IllegalArgumentException("分析记录不存在: " + id));
    if (entity.getImageUrl() != null && !entity.getImageUrl().isBlank()) {
      return entity.getImageUrl();
    }

    String prompt = entity.getImagePrompt();
    if (prompt == null || prompt.isBlank()) {
      prompt = infographicPromptBuilder.buildImagePromptFromResult(entity);
    }

    String imageUrl;
    try {
      imageUrl = senseNovaClient.generateImage(prompt);
    } catch (Exception e) {
      log.warn("信息图生成失败: {}", e.getMessage());
      throw new IllegalStateException("信息图生成失败: " + e.getMessage(), e);
    }
    entity.setImageUrl(imageUrl);
    entity.setImagePrompt(prompt);
    repo.save(entity);
    return imageUrl;
  }

  // ================================================================
  // 列表 / 查询
  // ================================================================

  public List<ProsperityPickRecentDTO> recent() {
    LocalDate cutoff = LocalDate.now().minusDays(RECENT_HISTORY_DAYS - 1L);
    return repo
        .findTop30ByAnalysisDateGreaterThanEqualOrderByAnalysisDateDescIdDesc(cutoff)
        .stream()
        .filter(e -> e.getDegraded() == null || e.getDegraded() != 1)
        .map(resultAnalyzer::toRecentDTO)
        .collect(Collectors.toList());
  }

  public ProsperityPickResultDTO get(Long id) {
    InvestProsperityPick entity =
        repo.findById(id).orElseThrow(() -> new IllegalArgumentException("分析记录不存在: " + id));
    TradeStockBasic basic =
        stockQueryService
            .resolveStock(entity.getStockCode())
            .orElseGet(
                () -> {
                  TradeStockBasic b = new TradeStockBasic();
                  b.setStockCode(entity.getStockCode());
                  b.setStockName(entity.getStockName());
                  return b;
                });
    return resultAnalyzer.toResultDTO(entity, basic, true);
  }

  /** 获取报告详情 HTML */
  @Transactional
  public String getReportHtml(Long id) {
    InvestProsperityPick entity =
        repo.findById(id).orElseThrow(() -> new IllegalArgumentException("分析记录不存在: " + id));
    if (entity.getReportHtml() != null && !entity.getReportHtml().isBlank()) {
      return entity.getReportHtml();
    }
    // 懒生成
    TradeStockBasic basic =
        stockQueryService
            .resolveStock(entity.getStockCode())
            .orElseGet(
                () -> {
                  TradeStockBasic b = new TradeStockBasic();
                  b.setStockCode(entity.getStockCode());
                  b.setStockName(entity.getStockName());
                  return b;
                });
    String html = reportRenderer.buildReportHtml(entity, basic);
    entity.setReportHtml(html);
    repo.save(entity);
    return html;
  }

  // ============ AI 系统提示词 ============

  private static final String SYSTEM_PROMPT =
      "你是一名资深的 A 股价值景气投资分析师，擅长从全球产业趋势、行业周期、国家政策、"
          + "公司基本面、管理层、估值、技术面、资金面进行全维度分析。"
          + "请严格按照用户给出的 JSON Schema 输出，不要使用 markdown，"
          + "不要输出任何解释或前后多余文字，输出必须是合法的 JSON。";
}
