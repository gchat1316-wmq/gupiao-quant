package com.quant.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.config.StockAnalysisProperties;
import com.quant.dto.stockanalysis.StockAnalysisRecordListDTO;
import com.quant.dto.stockanalysis.StockAnalysisRequest;
import com.quant.dto.stockanalysis.StockAnalysisResponse;
import com.quant.dto.stockanalysis.WindResearchContext;
import com.quant.entity.StockAnalysisRecord;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.StockAnalysisRecordRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;
import com.quant.service.prosperitystrong.WindAifinMarketClient;
import com.quant.service.search.WebSearchClient;
import com.quant.service.stockanalysis.WindResearchService;
import com.quant.service.tdx.TdxMcpClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 个股分析服务 (异步版) - submit: 创建 PENDING 记录, 立即返回 id - @Async executeAsync: 后台跑 baostock + 紫苏叶/九维, 写回 DB
 * - getById / list: 查询记录
 *
 * <p>缓存策略: 同 code + method 的 SUCCESS 记录 1 小时内直接复用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAnalysisService {

  private final StockAnalysisProperties properties;
  private final StockAnalysisRecordRepository repository;
  private final StockQueryService stockQueryService;
  private final TradeStockBasicRepository stockBasicRepository;
  private final TradeStockFinancialRepository financialRepository;
  private final MiniMaxClient miniMaxClient;
  private final SenseNovaClient senseNovaClient;
  private final WebSearchClient webSearchClient;
  private final WindAifinMarketClient windAifinMarketClient;
  private final TdxMcpClient tdxMcpClient;
  private final UnifiedStockResearchService unifiedStockResearchService;
  private final WindResearchService windResearchService;
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /** 缓存有效期 (小时) */
  private static final int CACHE_HOURS = 1;

  // ============================================================
  // 1. 提交任务 (立即返回 recordId)
  // ============================================================
  @Transactional
  public Long submit(StockAnalysisRequest req) {
    String codeRaw = req.getCode() == null ? "" : req.getCode().trim();
    if (codeRaw.isEmpty()) {
      throw new IllegalArgumentException("股票代码不能为空");
    }
    String code = unifiedStockResearchService.normalizeCode(codeRaw);
    String method = req.getMethod() == null ? "full" : req.getMethod();
    Integer years = req.getYears() == null ? 2 : req.getYears();
    Boolean lite = req.getLite() == null ? Boolean.TRUE : req.getLite();
    Integer quoteDays = req.getQuoteDays() == null ? 60 : req.getQuoteDays();

    // 缓存命中: 1小时内同 code+method 直接复用
    Pageable one = PageRequest.of(0, 1);
    var existing = repository.findLatestSuccess(code, method, one);
    if (!existing.isEmpty()) {
      StockAnalysisRecord r = existing.getContent().get(0);
      if (r.getFinishedAt() != null
          && r.getFinishedAt().isAfter(LocalDateTime.now().minusHours(CACHE_HOURS))) {
        log.info("缓存命中: code={} method={} recordId={}", code, method, r.getId());
        return r.getId();
      }
    }

    // 新建 PENDING 记录
    StockAnalysisRecord rec = new StockAnalysisRecord();
    rec.setStockCode(code);
    rec.setStockCodeRaw(codeRaw);
    rec.setMethod(method);
    rec.setYears(years);
    rec.setLite(lite ? 1 : 0);
    rec.setQuoteDays(quoteDays);
    rec.setStatus("PENDING");
    rec = repository.save(rec);
    log.info("提交个股分析: id={} code={} method={}", rec.getId(), code, method);
    return rec.getId();
  }

  // ============================================================
  // 2. 异步执行 (Spring 线程池)
  // ============================================================
  @Async("stockAnalysisExecutor")
  public void executeAsync(Long recordId) {
    StockAnalysisRecord rec = repository.findById(recordId).orElse(null);
    if (rec == null) {
      log.error("记录不存在: id={}", recordId);
      return;
    }
    if (!"PENDING".equals(rec.getStatus())) {
      log.warn("记录非 PENDING 状态, 跳过: id={} status={}", recordId, rec.getStatus());
      return;
    }
    // 更新为 RUNNING
    rec.setStatus("RUNNING");
    rec.setStartedAt(LocalDateTime.now());
    rec = repository.save(rec);

    long start = System.currentTimeMillis();
    try {
      StockAnalysisRequest req = new StockAnalysisRequest();
      req.setCode(rec.getStockCodeRaw());
      req.setMethod(rec.getMethod());
      req.setYears(rec.getYears());
      req.setLite(rec.getLite() == 1);
      req.setQuoteDays(rec.getQuoteDays());

      StockAnalysisResponse resp = doAnalyze(req);
      long elapsed = System.currentTimeMillis() - start;

      // 写回结果
      rec.setStatus("SUCCESS");
      rec.setFinishedAt(LocalDateTime.now());
      rec.setElapsedMs((int) elapsed);
      if (resp != null) {
        rec.setStockName(resp.getName());
        rec.setCurrentPrice(
            resp.getCurrentPrice() == null ? null : BigDecimal.valueOf(resp.getCurrentPrice()));
        rec.setVerdict(resp.getVerdict());
        rec.setMoatScore(resp.getMoatScore());
        rec.setResultJson(objectMapper.writeValueAsString(resp));
        rec.setReportHtml(resp.getReportHtml());
        rec.setSourcePayloadJson(
            objectMapper.writeValueAsString(
                Map.of(
                    "sourceMetadata",
                        resp.getSourceMetadata() == null
                            ? Collections.emptyMap()
                            : resp.getSourceMetadata(),
                    "rawData",
                        resp.getRawData() == null ? Collections.emptyMap() : resp.getRawData())));
      }
      repository.save(rec);
      log.info("分析完成: id={} code={} elapsed={}ms", recordId, rec.getStockCode(), elapsed);
    } catch (Exception e) {
      log.error("分析失败: id={}", recordId, e);
      rec.setStatus("FAILED");
      rec.setFinishedAt(LocalDateTime.now());
      rec.setElapsedMs((int) (System.currentTimeMillis() - start));
      rec.setErrorMessage(e.getMessage() == null ? e.getClass().getName() : e.getMessage());
      repository.save(rec);
    }
  }

  // ============================================================
  // 3. 同步版 (供 executeAsync 内部调用, 也可被外部直接调)
  // ============================================================
  public StockAnalysisResponse doAnalyze(StockAnalysisRequest req) {
    String code = unifiedStockResearchService.normalizeCode(req.getCode());
    String method = req.getMethod() == null ? "full" : req.getMethod();

    Map<String, Object> rawData = fetchPack(code, req);
    if (rawData == null || rawData.isEmpty()) {
      throw new RuntimeException("baostock 数据获取失败");
    }
    TradeStockBasic basic =
        stockQueryService
            .resolveStock(code)
            .orElseGet(
                () -> {
                  TradeStockBasic synthetic = new TradeStockBasic();
                  synthetic.setStockCode(code);
                  synthetic.setStockName(
                      String.valueOf(asMap(rawData.get("basic")).getOrDefault("code_name", code)));
                  return synthetic;
                });
    // 拉一次 Wind 研报 + 一致预期（24h 缓存, 失败降级）
    WindResearchContext windResearch =
        windResearchService.fetch(code, basic.getStockName(), method);
    Map<String, Object> aiAnalysis =
        analyzeWithAi(buildPrompt(basic, rawData, method, windResearch), method);
    return unifiedStockResearchService.buildUnifiedResponse(
        basic, rawData, aiAnalysis, method, 0L, windResearch);
  }

  // ============================================================
  // 4. 查询接口
  // ============================================================
  public StockAnalysisRecord getById(Long id) {
    return repository.findById(id).orElse(null);
  }

  public StockAnalysisRecord save(StockAnalysisRecord rec) {
    return repository.save(rec);
  }

  public void deleteById(Long id) {
    repository.deleteById(id);
  }

  public StockAnalysisResponse parseRecordJson(StockAnalysisRecord rec) {
    if (rec == null || rec.getResultJson() == null) return null;
    try {
      return objectMapper.readValue(rec.getResultJson(), StockAnalysisResponse.class);
    } catch (Exception e) {
      log.warn("解析 result_json 失败: id={}", rec.getId(), e);
      return null;
    }
  }

  public Page<StockAnalysisRecordListDTO> list(String kw, String status, int page, int size) {
    Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
    Page<StockAnalysisRecord> p = repository.search(kw, status, pageable);
    Map<String, String> realNames = lookupRealNames(p.getContent());
    return p.map(r -> toListDTO(r, realNames));
  }

  public List<StockAnalysisRecordListDTO> toListDTOList(List<StockAnalysisRecord> records) {
    Map<String, String> realNames = lookupRealNames(records);
    return records.stream().map(r -> toListDTO(r, realNames)).toList();
  }

  /**
   * 用 stockCodeRaw 从 trade_stock_basic 批量补全真名。 历史 stock_analysis_record.stock_name
   * 字段很多写的是代码（"sh.688401"）而不是真名， 这里做兜底——只在 stockName 看起来不像真名时用 trade_stock_basic 里的真名替换。
   */
  private Map<String, String> lookupRealNames(List<StockAnalysisRecord> records) {
    if (records == null || records.isEmpty()) return Collections.emptyMap();
    Map<String, String> result = new HashMap<>();
    for (StockAnalysisRecord r : records) {
      String raw = r.getStockCodeRaw();
      String stored = r.getStockName();
      if (raw == null || raw.isBlank()) continue;
      if (!looksLikeCode(stored)) continue;
      if (result.containsKey(raw)) continue;
      try {
        // 裸代码 "688401" → "688401.SH" / "688401.SZ"
        List<TradeStockBasic> matches = stockBasicRepository.findByStockCodePrefix(raw);
        if (!matches.isEmpty()
            && matches.get(0).getStockName() != null
            && !matches.get(0).getStockName().isBlank()) {
          result.put(raw, matches.get(0).getStockName());
        }
      } catch (Exception e) {
        log.debug("补全真名失败: codeRaw={}", raw, e);
      }
    }
    return result;
  }

  private boolean looksLikeCode(String s) {
    if (s == null) return false;
    String t = s.trim();
    if (t.isEmpty()) return false;
    // BaoStock 风格 "sh.688401" / "sz.002920"
    if (t.contains(".")) return true;
    // 罕见风格 "sh688401"
    String lower = t.toLowerCase();
    if ((lower.startsWith("sh") || lower.startsWith("sz"))
        && t.length() > 2
        && Character.isDigit(t.charAt(t.length() - 1))) {
      return true;
    }
    return false;
  }

  private StockAnalysisRecordListDTO toListDTO(
      StockAnalysisRecord r, Map<String, String> realNames) {
    String summaryOneLiner = null;
    Integer sourceCoverage = null;
    boolean hasReport = r.getReportHtml() != null && !r.getReportHtml().isBlank();
    if (r.getResultJson() != null && !r.getResultJson().isBlank()) {
      try {
        StockAnalysisResponse response =
            objectMapper.readValue(r.getResultJson(), StockAnalysisResponse.class);
        Map<String, Object> summary =
            response.getAnalysis() == null
                ? Collections.emptyMap()
                : asMap(response.getAnalysis().get("summary"));
        summaryOneLiner =
            summary.get("oneLiner") == null
                ? response.getVerdict()
                : String.valueOf(summary.get("oneLiner"));
        sourceCoverage = countAvailableSources(response.getSourceMetadata());
        hasReport =
            hasReport || (response.getReportHtml() != null && !response.getReportHtml().isBlank());
      } catch (Exception e) {
        log.debug("列表解析富报告失败: id={}", r.getId(), e);
      }
    }
    String resolvedName = r.getStockName();
    if (looksLikeCode(resolvedName) && r.getStockCodeRaw() != null) {
      String realName = realNames.get(r.getStockCodeRaw());
      if (realName != null && !realName.isBlank()) {
        resolvedName = realName;
      }
    }
    return StockAnalysisRecordListDTO.builder()
        .id(r.getId())
        .stockCode(r.getStockCode())
        .stockCodeRaw(r.getStockCodeRaw())
        .stockName(resolvedName)
        .method(r.getMethod())
        .status(r.getStatus())
        .verdict(r.getVerdict())
        .moatScore(r.getMoatScore())
        .currentPrice(r.getCurrentPrice())
        .elapsedMs(r.getElapsedMs())
        .errorMessage(r.getErrorMessage())
        .summaryOneLiner(summaryOneLiner)
        .sourceCoverage(sourceCoverage)
        .hasReport(hasReport)
        .submittedAt(r.getSubmittedAt())
        .startedAt(r.getStartedAt())
        .finishedAt(r.getFinishedAt())
        .build();
  }

  private int countAvailableSources(Map<String, Object> sourceMetadata) {
    if (sourceMetadata == null || sourceMetadata.isEmpty()) return 0;
    int count = 0;
    for (Object meta : sourceMetadata.values()) {
      if (meta instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("available"))) {
        count++;
      }
    }
    return count;
  }

  private Map<String, Object> analyzeWithAi(String prompt, String method) {
    String sysPrompt =
        "five_dimension".equalsIgnoreCase(method) ? FIVE_DIM_SYSTEM_PROMPT : SYSTEM_PROMPT;
    Exception miniMaxError;
    try {
      return parseAiJson(miniMaxClient.chatComplete(sysPrompt, prompt));
    } catch (Exception e) {
      miniMaxError = e;
      log.warn("MiniMax 分析失败，尝试 SenseNova: {}", e.getMessage());
    }
    try {
      return parseAiJson(senseNovaClient.chatComplete(sysPrompt, prompt));
    } catch (Exception senseNovaError) {
      String message =
          "MiniMax: " + miniMaxError.getMessage() + "; SenseNova: " + senseNovaError.getMessage();
      throw new IllegalStateException("AI 调用失败: " + message, senseNovaError);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseAiJson(String raw) {
    try {
      return objectMapper.readValue(extractJson(raw), Map.class);
    } catch (Exception e) {
      throw new IllegalStateException("AI 返回不是合法 JSON: " + e.getMessage(), e);
    }
  }

  private String buildPrompt(
      TradeStockBasic basic,
      Map<String, Object> rawData,
      String method,
      WindResearchContext windResearch) {
    StringBuilder sb = new StringBuilder();
    boolean isFiveDim = "five_dimension".equalsIgnoreCase(method);
    sb.append("分析日期: ").append(java.time.LocalDate.now()).append("\n");
    sb.append("公司: ")
        .append(basic.getStockName())
        .append(" ")
        .append(basic.getStockCode())
        .append(" (A股)\n");
    if (basic.getSectorNames() != null)
      sb.append("所属行业: ").append(basic.getSectorNames()).append("\n");
    if (basic.getPeTtm() != null) sb.append("PE-TTM: ").append(basic.getPeTtm()).append("\n");
    if (basic.getPb() != null) sb.append("PB: ").append(basic.getPb()).append("\n");
    if (basic.getPsTtm() != null) sb.append("PS-TTM: ").append(basic.getPsTtm()).append("\n");
    if (basic.getTotalShares() != null)
      sb.append("总股本: ").append(basic.getTotalShares()).append(" 亿股\n");

    List<TradeStockFinancial> records =
        financialRepository.findByStockCodeOrderByReportDateDesc(basic.getStockCode()).stream()
            .limit(12)
            .toList();
    if (!records.isEmpty()) {
      sb.append("\n最近 ").append(records.size()).append(" 季度财务（单位：元）:\n");
      sb.append("报告期 | 营收 | 净利润 | EPS | ROE | 毛利率 | 净利率 | 营收同比 | 扣非同比\n");
      for (TradeStockFinancial f : records) {
        sb.append(f.getReportDate())
            .append(" | ")
            .append(safe(f.getRevenue()))
            .append(" | ")
            .append(safe(f.getNetProfit()))
            .append(" | ")
            .append(safe(f.getEps()))
            .append(" | ")
            .append(safe(f.getRoe()))
            .append(" | ")
            .append(safe(f.getGrossMargin()))
            .append(" | ")
            .append(safe(f.getNetMargin()))
            .append(" | ")
            .append(safe(f.getRevenueYoy()))
            .append(" | ")
            .append(safe(f.getDeductedNetProfitYoy()))
            .append("\n");
      }
    }

    Map<String, Object> quote = asMap(rawData.get("quote"));
    if (!quote.isEmpty()) {
      sb.append("\nbaostock 行情数据:\n");
      sb.append("收盘: ").append(safe(quote.get("close"))).append("\n");
      sb.append("成交量: ").append(safe(quote.get("volume"))).append("\n");
      sb.append("换手率: ").append(safe(quote.get("turn"))).append("\n");
      sb.append("区间最高: ").append(safe(quote.get("period_high"))).append("\n");
      sb.append("区间最低: ").append(safe(quote.get("period_low"))).append("\n");
      sb.append("区间涨跌幅: ").append(safe(quote.get("period_change_pct"))).append("\n");
    }
    List<Object> finHistory = asList(rawData.get("financial_history"));
    if (!finHistory.isEmpty()) {
      sb.append("\nbaostock 财务历史 (近 ").append(finHistory.size()).append(" 季度):\n");
      sb.append("报告期 | ROE | 毛利率 | 净利率 | 营收YoY | 净利YoY\n");
      for (Object item : finHistory) {
        Map<String, Object> rec = asMap(item);
        Map<String, Object> p = asMap(rec.get("profitability"));
        Map<String, Object> g = asMap(rec.get("growth"));
        sb.append(safe(rec.get("statDate")))
            .append(" | ")
            .append(safe(p.get("roe_avg")))
            .append(" | ")
            .append(safe(p.get("gp_margin")))
            .append(" | ")
            .append(safe(p.get("np_margin")))
            .append(" | ")
            .append(safe(g.get("yoy_revenue")))
            .append(" | ")
            .append(safe(g.get("yoy_ni")))
            .append("\n");
      }
    }
    if (!asList(rawData.get("forecast")).isEmpty()) {
      sb.append("\nforecast 数据:\n");
      for (Object item : asList(rawData.get("forecast"))) {
        sb.append("- ").append(safe(item)).append("\n");
      }
    }

    if (webSearchClient.isEnabled()) {
      String name = basic.getStockName();
      if (isFiveDim) {
        appendSearch(sb, name + " 产业链 行业地位 卡脖子 国产替代 全球玩家");
        appendSearch(sb, name + " 订单 产能 客户结构 大客户认证 第二曲线 业务拆分");
        appendSearch(sb, name + " 估值 目标价 机构预测 券商研报 PS PE");
        appendSearch(sb, name + " 风险 瓶颈 产能爬坡 地缘 供应链");
        sb.append("\n⚠️ 重要约束：产业链深度数据（设备数量、产能、订单排期等）如果联网检索不到，");
        sb.append("请明确在对应字段写\"未检索到，待人工核实\"，**禁止编造具体数字**。\n");
      } else {
        appendSearch(sb, name + " 行业景气度 机构预测 目标价");
        appendSearch(sb, name + " 主力资金 北向资金 龙虎榜");
      }
    } else {
      sb.append("\n（未启用联网检索，请仅基于已知信息分析）\n");
    }

    if (isFiveDim) {
      // 数据源 1：Wind financial_docs（公告 + 财经新闻 + 投资者互动 RAG）
      appendWindFinancialDocs(sb, basic.getStockName());
      // 数据源 2：通达信 MCP 财务数据（NL 查询,补充 Wind 拿不到的结构化财务/行业数据）
      appendTdxFinanceData(sb, basic.getStockName());
    }

    // 4 种方法都接入：Wind 一致预期 (强制 AI 引用) + 研报片段 (按 method 加权)
    // 取代五维独享的 Wind 调用, 让 full / purple_perilla / gaojingqi 也能拿到卖方研报
    appendWindResearchContext(sb, windResearch, method);

    sb.append("\n请严格按照下方 JSON 格式输出，不要输出任何额外文字、不要使用 markdown：\n");
    sb.append(isFiveDim ? FIVE_DIM_JSON_SCHEMA : JSON_SCHEMA);
    return sb.toString();
  }

  /**
   * 把 Wind 研报 + 一致预期塞进 prompt。 设计要点： 1. 一致预期 (target price / rating / EPS) 是估值段的最高优先级证据——强制 AI 引用。
   * 2. 研报片段按 method 加权：purple/gaojingqi 给完整 3-5 条，full/五维 给 1-2 条摘要。 3. 失败/未启用时只写"未启用", 不抛错,
   * 不影响主报告。
   */
  private void appendWindResearchContext(StringBuilder sb, WindResearchContext ctx, String method) {
    if (ctx == null) {
      sb.append("\n（Wind 研报：本次未拉取，跳过）\n");
      return;
    }
    if (!ctx.isWindInstalled() || !ctx.isWindHasKey()) {
      sb.append("\n（Wind 研报：未安装或无 API Key，跳过）\n");
      return;
    }
    if (!ctx.isAvailable()) {
      sb.append("\n（Wind 研报：本次拉取无可用数据，不影响主报告）\n");
      return;
    }

    // ===== 一致预期 (强制 AI 引用) =====
    WindResearchContext.Consensus c = ctx.getConsensus();
    sb.append("\n【Wind 一致预期（卖方共识, 估值段最高优先级证据 ⚠️）】\n");
    if (c != null && c.getSourceRowCount() > 0) {
      if (c.getRating() != null) sb.append("  综合评级: ").append(c.getRating()).append("\n");
      if (c.getTargetPrice() != null)
        sb.append("  一致预期目标价: ").append(c.getTargetPrice()).append(" 元\n");
      if (c.getCurrency() != null) sb.append("  货币: ").append(c.getCurrency()).append("\n");
      if (c.getEps2026() != null)
        sb.append("  一致预期 2026 EPS: ").append(c.getEps2026()).append(" 元\n");
      if (c.getEps2027() != null)
        sb.append("  一致预期 2027 EPS: ").append(c.getEps2027()).append(" 元\n");
      if (c.getNetProfitGrowth2026() != null)
        sb.append("  一致预期 2026 净利同比: ").append(c.getNetProfitGrowth2026()).append("%\n");
      if (c.getNetProfitGrowth2027() != null)
        sb.append("  一致预期 2027 净利同比: ").append(c.getNetProfitGrowth2027()).append("%\n");
    } else {
      sb.append("  （本次未取到一致预期结构化数据，仅供参考）\n");
    }
    sb.append(
        "\n⚠️ 强制要求: 你的估值段 (target2026 / target2027 / verdict / reasoning) 必须围绕上述一致预期目标价和 EPS 生成。\n");
    sb.append("  - 如果 AI 推算目标价与一致预期偏离 ±20% 以上, 必须在 reasoning 字段说明偏离原因。\n");
    sb.append("  - 一致预期未提供具体数字时, 可以自由推算, 但仍需引用评级 (增持/买入/中性) 作为定性锚点。\n");
    sb.append("  - 不允许完全忽略一致预期, 不允许编造评级。\n");

    // ===== 研报片段 (按 method 加权) =====
    List<WindResearchContext.ResearchExcerpt> reports = ctx.getReports();
    if (reports == null || reports.isEmpty()) {
      sb.append("\n（Wind 研报片段: 本次未检索到）\n");
      return;
    }
    // 展示条数: purple_perilla / gaojingqi 拉满(5), full / 五维 压缩(2)
    int maxShow =
        ("purple_perilla".equalsIgnoreCase(method) || "gaojingqi".equalsIgnoreCase(method)) ? 5 : 2;
    List<WindResearchContext.ResearchExcerpt> picked =
        reports.subList(0, Math.min(maxShow, reports.size()));

    sb.append("\n【Wind 研报片段（来自 financial_docs 检索, doc_type=")
        .append(picked.get(0).getDocType())
        .append(")】\n");
    for (int i = 0; i < picked.size(); i++) {
      WindResearchContext.ResearchExcerpt r = picked.get(i);
      sb.append("\n▍研报 #").append(i + 1);
      if (r.getSource() != null) sb.append(" | ").append(r.getSource());
      if (r.getDate() != null) sb.append(" | ").append(r.getDate());
      sb.append("\n  标题: ").append(safe(r.getTitle())).append("\n");
      sb.append("  摘要: ").append(safe(r.getContent())).append("\n");
    }
    sb.append("\n⚠️ 上面是从 Wind 卖方研报/财经媒体抓到的片段, 优先级高于普通联网检索。\n");
    sb.append("  - 卖方对景气度/产业链/竞争格局/估值锚点的判断 → 写入对应维度\n");
    sb.append("  - 与现有数据冲突时, 以研报为准, 但需在 reasoning 说明\n");
  }

  /**
   * Wind financial_docs RAG：拉取本股的公告 + 财经新闻 + 投资者互动答复。 这部分数据**优先级最高**——尤其是投资者互动答复，会包含 HBM 龙头、订单细节等
   * 公开但不直接写在招股书里的关键信息。
   */
  private void appendWindFinancialDocs(StringBuilder sb, String stockName) {
    if (!windAifinMarketClient.isInstalled() || !windAifinMarketClient.hasApiKey()) {
      sb.append("\n（Wind financial_docs 未启用或无 API Key，跳过）\n");
      return;
    }
    sb.append("\n【Wind financial_docs 检索 · 高优先级证据（公告 + 财经新闻 + 投资者互动）】\n");
    // 用公司名去空格做 query 关键字（Wind 不接受 query 含空格）
    String compactName = stockName == null ? "" : stockName.replaceAll("\\s+", "");
    // 三类检索：
    //   1. 投资者互动/HBM/合作（最能体现"业务定位"）
    //   2. 财务公告/订单/产能（业绩兑现）
    //   3. 一般新闻（市场情绪）
    try {
      // 1. 投资者互动答复：HBM/订单/合作/客户/产能/在手订单/海外
      fetchAndAppendWindNews(
          sb,
          "financial_docs",
          "get_financial_news",
          Map.of("query", compactName + "HBM订单", "top_k", 3),
          "▍投资者互动/HBM/订单");
      fetchAndAppendWindNews(
          sb,
          "financial_docs",
          "get_financial_news",
          Map.of("query", compactName + "海外客户", "top_k", 3),
          "▍海外大客户/三星/SK海力士");
      fetchAndAppendWindNews(
          sb,
          "financial_docs",
          "get_financial_news",
          Map.of("query", compactName + "产能", "top_k", 3),
          "▍产能/募投/南浔");
    } catch (Exception e) {
      log.warn("Wind financial_docs 检索失败: {}", e.getMessage());
      sb.append("（Wind 检索异常: ").append(e.getMessage()).append("）\n");
    }
    // 2. 公告
    try {
      fetchAndAppendWindNews(
          sb,
          "financial_docs",
          "get_company_announcements",
          Map.of("query", compactName + "半导体", "top_k", 2),
          "▍公司公告/半导体");
      fetchAndAppendWindNews(
          sb,
          "financial_docs",
          "get_company_announcements",
          Map.of("query", compactName + "2025年报", "top_k", 2),
          "▍公司公告/2025 年报");
    } catch (Exception e) {
      log.warn("Wind announcements 检索失败: {}", e.getMessage());
    }
    sb.append("\n⚠️ Wind financial_docs 是 RAG 检索结果（基于上交所/深交所/财经媒体原始数据）。\n");
    sb.append("其中\"投资者互动\"板块的答复是公司官方回应，**优先级最高**——尤其涉及客户/订单/HBM/产能/海外认证的答复，必须当作高确定性证据写入对应维度。\n");
  }

  /**
   * 通达信 MCP 财务数据：用 NL 查询补 Wind 拿不到的结构化数据（营收/净利同比、EPS、行业地位描述）。 Wind financial_docs 主要拿文本类证据（HBM
   * 定位、订单细节、投资者互动）； TDX 拿结构化财务——两个数据源互补，不重复。
   */
  private void appendTdxFinanceData(StringBuilder sb, String stockName) {
    if (!tdxMcpClient.isAuthorized()) {
      sb.append("\n（TDX 通达信 MCP 未配置 API Key, 跳过结构化财务查询。\n");
      sb.append("  如需启用, 请在 application.yml 设置 prosperity-strong.tdx.api-key = TDX-c62ebd01...\n");
      sb.append("  或环境变量 TDX_API_KEY=...）\n");
      return;
    }
    sb.append("\n【通达信 MCP 结构化财务数据（NL 查询,补充 Wind 拿不到的具体数字）】\n");
    String stockCode = resolveStockCode(stockName);
    if (stockCode == null || stockCode.isBlank()) {
      sb.append("（未能解析股票代码, 跳过 TDX 查询）\n");
      return;
    }
    // 4 个互补 query
    try {
      tdxAskAppend(sb, stockCode, stockCode + " 2025年报 关键财务指标", "▍2025 年报关键指标");
      tdxAskAppend(sb, stockCode, stockCode + " 主营业务收入 同比", "▍最新营收/利润同比");
      tdxAskAppend(sb, stockCode, stockCode + " 行业地位", "▍行业地位/投资逻辑");
      tdxAskAppend(sb, stockCode, stockCode + " 一致预期 EPS", "▍一致预期 EPS（卖方共识）");
    } catch (Exception e) {
      log.warn("TDX 财务查询失败: {}", e.getMessage());
      sb.append("（TDX 财务查询异常: ").append(e.getMessage()).append("）\n");
    }
    sb.append("\n⚠️ 上面是 TDX 通过自然语言问出的结构化数据（营收/净利同比/行业地位描述）。\n");
    sb.append("这些是**结构化字段**（不是研报文本），优先级与 Wind 文本证据相当。\n");
    sb.append("  - 营收/净利同比 → 写入业绩兑现度（\"当期财报验证\"）\n");
    sb.append("  - 行业地位/主营关键字 → 写入稀缺卡位（\"卡位赛道\"）\n");
    sb.append("  - 一致预期 EPS → 写入估值阶梯（作为 AI 推测的辅助锚点）\n");
    sb.append("如果返回 total=0, 跳过即可, 不要编造数据。\n");
  }

  private void tdxAskAppend(StringBuilder sb, String stockCode, String question, String label) {
    java.util.Optional<com.fasterxml.jackson.databind.JsonNode> respOpt =
        tdxMcpClient.ask(question);
    sb.append("\n").append(label).append("：\n");
    if (respOpt == null || respOpt.isEmpty()) {
      sb.append("  （未返回, 跳过）\n");
      return;
    }
    int total = respOpt.get().path("meta").path("total").asInt(0);
    if (total == 0) {
      sb.append("  （TDX 返回 0 条, 此 query 不适用此股）\n");
      return;
    }
    String table = com.quant.service.tdx.TdxMcpClient.tableToText(respOpt.get(), 5);
    sb.append("  ").append(table.replace("\n", "\n  "));
  }

  private String resolveStockCode(String stockName) {
    if (stockName == null) return null;
    // 输入可能是 "赛腾股份" 或 "603283" 或 "sh.603283" 或 "603283.SH"
    String s = stockName.trim();
    // 已经是代码
    if (s.matches(".*\\d{6}.*")) {
      return s.replaceAll("[^0-9]", "").substring(0, 6);
    }
    // 否则查 trade_stock_basic
    try {
      java.util.Optional<com.quant.entity.TradeStockBasic> basic =
          stockQueryService.resolveStock(s);
      return basic.map(com.quant.entity.TradeStockBasic::getStockCode).orElse(null);
    } catch (Exception e) {
      return null;
    }
  }

  /** 调一次 Wind financial_docs 工具，提取 items[].content/title/date，拼成文本塞进 sb。 */
  private void fetchAndAppendWindNews(
      StringBuilder sb,
      String serverType,
      String toolName,
      Map<String, Object> args,
      String label) {
    try {
      com.fasterxml.jackson.databind.JsonNode root =
          windAifinMarketClient.call(serverType, toolName, args);
      if (root == null) return;
      // 工具返回结构: content[0].text -> JSON string -> {data:{items:[{title, content, date, doc_type,
      // relevance}]}}
      com.fasterxml.jackson.databind.JsonNode firstContent = root.get("content");
      if (firstContent == null || !firstContent.isArray() || firstContent.isEmpty()) return;
      com.fasterxml.jackson.databind.JsonNode first = firstContent.get(0);
      com.fasterxml.jackson.databind.JsonNode textNode = first == null ? null : first.get("text");
      if (textNode == null) return;
      String text = textNode.asText();
      // text 是个 JSON string，再解析
      com.fasterxml.jackson.databind.JsonNode inner = objectMapper.readTree(text);
      com.fasterxml.jackson.databind.JsonNode data = inner == null ? null : inner.get("data");
      com.fasterxml.jackson.databind.JsonNode items = data == null ? null : data.get("items");
      if (items == null || !items.isArray() || items.isEmpty()) {
        sb.append(label).append("：未检索到。\n");
        return;
      }
      sb.append("\n").append(label).append("：\n");
      int shown = 0;
      for (com.fasterxml.jackson.databind.JsonNode item : items) {
        if (shown >= 3) break;
        String title = item.path("title").asText("");
        String content = item.path("content").asText("");
        String date = item.path("date").asText("");
        if (content.length() > 500) content = content.substring(0, 500) + "...";
        sb.append("  - [")
            .append(date)
            .append("] ")
            .append(title)
            .append("\n")
            .append("    ")
            .append(content)
            .append("\n");
        shown++;
      }
    } catch (Exception e) {
      sb.append(label).append("：检索失败 - ").append(e.getMessage()).append("\n");
    }
  }

  private void appendSearch(StringBuilder sb, String query) {
    List<WebSearchClient.SearchResult> results = webSearchClient.search(query);
    if (results.isEmpty()) return;
    sb.append("【").append(query).append("】\n");
    for (WebSearchClient.SearchResult result : results) {
      sb.append(result.toLine()).append("\n");
    }
  }

  private String extractJson(String raw) {
    if (raw == null) return "{}";
    String s = raw.trim();
    if (s.startsWith("```")) {
      int firstNewline = s.indexOf('\n');
      if (firstNewline > 0) s = s.substring(firstNewline + 1);
      int lastFence = s.lastIndexOf("```");
      if (lastFence > 0) s = s.substring(0, lastFence);
    }
    int start = s.indexOf('{');
    int end = s.lastIndexOf('}');
    return start >= 0 && end > start ? s.substring(start, end + 1) : s;
  }

  // ============================================================
  // 5. 调 baostock (从原 service 搬过来)
  // ============================================================
  @SuppressWarnings("unchecked")
  private Map<String, Object> fetchPack(String code, StockAnalysisRequest req) {
    try {
      List<String> cmd =
          new ArrayList<>(
              List.of(
                  properties.getPythonCommand(),
                  properties.getPythonScript(),
                  "pack",
                  code,
                  String.valueOf(req.getQuoteDays() == null ? 60 : req.getQuoteDays()),
                  String.valueOf(req.getYears() == null ? 2 : req.getYears())));
      if (Boolean.TRUE.equals(req.getLite())) {
        cmd.add("--lite");
      }
      log.info("调 baostock: {}", String.join(" ", cmd));
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      Process process = pb.start();
      StringBuilder stdout = new StringBuilder();
      try (var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) stdout.append(line);
      }
      boolean done =
          process.waitFor(properties.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
      if (!done) {
        process.destroyForcibly();
        throw new RuntimeException("baostock 调用超时 (" + properties.getTimeoutSeconds() + "s)");
      }
      if (process.exitValue() != 0) {
        throw new RuntimeException("baostock 退出码 " + process.exitValue() + ": " + stdout);
      }
      String content = stdout.toString();
      int idx = content.indexOf('{');
      if (idx < 0) throw new RuntimeException("baostock 输出无 JSON");
      return objectMapper.readValue(content.substring(idx), Map.class);
    } catch (Exception e) {
      throw new RuntimeException("baostock 调用失败: " + e.getMessage(), e);
    }
  }

  // ============================================================
  // 6. 紫苏叶 + 高景气九维 (从原 service 搬过来, 略)
  // ============================================================
  private Map<String, Object> runPurplePerilla(Map<String, Object> raw, String name) {
    Map<String, Object> result = new HashMap<>();
    Map<String, Object> industry = asMap(raw.get("industry"));
    String industryName =
        industry == null ? "未知" : String.valueOf(industry.getOrDefault("industry", "未知"));
    Map<String, Object> chain = new HashMap<>();
    chain.put("industry", industryName);
    chain.put("name", name);
    chain.put("layer", inferLayer(industryName, name));
    chain.put("chainPath", inferChainPath(industryName, name));
    chain.put("moatType", inferMoatType(industryName, name));
    result.put("chainPosition", chain);
    Map<String, Object> comp = new HashMap<>();
    comp.put("globalPlayers", inferCompetitors(industryName, name));
    comp.put("chinesePosition", inferChinesePosition(industryName, name));
    comp.put("geographicAdvantage", inferGeoAdvantage(industryName, name));
    result.put("competition", comp);
    Map<String, Object> q = new HashMap<>();
    q.put("Q1_irreplaceable", "需要核实 - 该环节全球供应商数量与对标分析");
    q.put("Q2_competitorCount", "需要核实 - 国内/全球具体玩家数");
    q.put("Q3_demandTrend", "需要核实 - 下游Capex订单趋势");
    q.put("note", "本数据为占位提示, 需结合个股非结构化调研");
    result.put("threeQuestions", q);
    int moat = calcMoat(industryName, name);
    result.put("moatScore", moat);
    String verdict;
    if (moat >= 8) verdict = "盯住/就是它了";
    else if (moat >= 6) verdict = "盯住";
    else if (moat >= 4) verdict = "观望";
    else verdict = "回避";
    result.put("verdict", verdict);
    return result;
  }

  private Map<String, Object> runGaoJingQi(Map<String, Object> raw, String name, Double price) {
    Map<String, Object> nine = new HashMap<>();
    List<Object> finHistory = asList(raw.get("financial_history"));
    Map<String, Object> fin = new HashMap<>();
    if (!finHistory.isEmpty()) {
      Map<String, Object> latest = asMap(finHistory.get(finHistory.size() - 1));
      Map<String, Object> prof = asMap(latest.get("profitability"));
      Map<String, Object> growth = asMap(latest.get("growth"));
      fin.put("latestPeriod", latest.get("statDate"));
      fin.put("revenue", parseDouble(prof == null ? null : prof.get("revenue")));
      fin.put("roe", formatPct(prof == null ? null : prof.get("roe_avg")));
      fin.put("grossMargin", formatPct(prof == null ? null : prof.get("gp_margin")));
      fin.put("netMargin", formatPct(prof == null ? null : prof.get("np_margin")));
      fin.put("yoyRevenue", formatPct(growth == null ? null : growth.get("yoy_revenue")));
      fin.put("yoyNetProfit", formatPct(growth == null ? null : growth.get("yoy_ni")));
      fin.put("epsTtm", parseDouble(prof == null ? null : prof.get("eps_ttm")));
    }
    nine.put("financial", fin);
    Map<String, Object> valuation = new HashMap<>();
    valuation.put("currentPrice", price);
    valuation.put("peTtm", "N/A (需用 eastmoney / Wind)");
    valuation.put("note", "Baostock 不提供 PE/PB/PS 估值字段");
    nine.put("valuation", valuation);
    Map<String, Object> quote = asMap(raw.get("quote"));
    Map<String, Object> mkt = new HashMap<>();
    mkt.put("close", parseDouble(quote == null ? null : quote.get("close")));
    mkt.put("turnover", formatPct(quote == null ? null : quote.get("turn")));
    mkt.put("volume", parseDouble(quote == null ? null : quote.get("volume")));
    if (quote != null && quote.containsKey("period_high")) {
      mkt.put("periodHigh", parseDouble(quote.get("period_high")));
      mkt.put("periodLow", parseDouble(quote.get("period_low")));
      mkt.put("periodChangePct", formatPct(quote.get("period_change_pct")));
    }
    nine.put("market", mkt);
    nine.put("company", asMap(raw.get("basic")));
    nine.put("industry", asMap(raw.get("industry")));
    nine.put("forecast", raw.get("forecast"));
    nine.put("dividend", raw.get("dividend"));
    return nine;
  }

  private Map<String, Object> buildFinancialSummary(List<Object> finHistory) {
    Map<String, Object> summary = new HashMap<>();
    if (finHistory == null || finHistory.isEmpty()) return summary;
    summary.put("periods", finHistory.size());
    List<String> periodLabels = new ArrayList<>();
    List<Double> roeList = new ArrayList<>();
    List<Double> gmList = new ArrayList<>();
    List<Double> nmList = new ArrayList<>();
    List<Double> yoyNiList = new ArrayList<>();
    for (Object o : finHistory) {
      Map<String, Object> rec = asMap(o);
      periodLabels.add(String.valueOf(rec.get("statDate")));
      Map<String, Object> p = asMap(rec.get("profitability"));
      Map<String, Object> g = asMap(rec.get("growth"));
      roeList.add(p == null ? null : parseDouble(p.get("roe_avg")));
      gmList.add(p == null ? null : parseDouble(p.get("gp_margin")));
      nmList.add(p == null ? null : parseDouble(p.get("np_margin")));
      yoyNiList.add(g == null ? null : parseDouble(g.get("yoy_ni")));
    }
    summary.put("periodLabels", periodLabels);
    summary.put("roeList", roeList);
    summary.put("grossMarginList", gmList);
    summary.put("netMarginList", nmList);
    summary.put("yoyNetProfitList", yoyNiList);
    return summary;
  }

  private List<String> buildCatalysts(Map<String, Object> raw, String name) {
    List<String> catalysts = new ArrayList<>();
    Object forecast = raw.get("forecast");
    if (forecast instanceof List<?> list && !list.isEmpty()) {
      catalysts.add("📢 业绩预告/快报: " + list.size() + " 条记录");
    }
    List<Object> finHistory = asList(raw.get("financial_history"));
    if (finHistory.size() >= 2) {
      Map<String, Object> latest = asMap(finHistory.get(finHistory.size() - 1));
      Map<String, Object> prev = asMap(finHistory.get(finHistory.size() - 2));
      Map<String, Object> lp = asMap(latest.get("profitability"));
      Map<String, Object> pp = asMap(prev.get("profitability"));
      Double curNm = parseDouble(lp == null ? null : lp.get("np_margin"));
      Double preNm = parseDouble(pp == null ? null : pp.get("np_margin"));
      if (curNm != null && preNm != null && curNm - preNm > 0.05) {
        catalysts.add(String.format("🔥 净利率季度环比 +%.1fpp, 业绩反转信号", (curNm - preNm) * 100));
      }
    }
    catalysts.add("🏭 关注下游Capec指引与新签订单公告");
    return catalysts;
  }

  private List<String> buildRisks(Map<String, Object> raw, String name) {
    List<String> risks = new ArrayList<>();
    List<Object> finHistory = asList(raw.get("financial_history"));
    if (!finHistory.isEmpty()) {
      Map<String, Object> latest = asMap(finHistory.get(finHistory.size() - 1));
      Map<String, Object> p = asMap(latest.get("profitability"));
      Double roe = parseDouble(p == null ? null : p.get("roe_avg"));
      Double nm = parseDouble(p == null ? null : p.get("np_margin"));
      if (roe != null && roe < 0.05) risks.add(String.format("⚠️ ROE仅%.2f%%, 盈利质量弱", roe * 100));
      if (nm != null && nm < 0) risks.add("⚠️ 净利率为负, 经营亏损");
    }
    risks.add("⚠️ 客户集中度风险: 半导体设备公司前五大客户占比通常 >60%");
    risks.add("⚠️ 应收账款周期长, 现金流压力需关注");
    risks.add("⚠️ 行业β波动大, 短期受市场情绪影响显著");
    return risks;
  }

  private String inferLayer(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("电子") || industry.contains("C35")) {
      if (name.contains("测") || name.contains("精")) return "第4层 - 测试设备";
      if (name.contains("蚀")) return "第3层 - 刻蚀设备";
      if (name.contains("光")) return "第3层 - 光刻/检测设备";
    }
    if (industry.contains("医药") || industry.contains("生物")) return "第3-4层 - 创新药/医疗器械";
    return "需结合个股业务定位";
  }

  private String inferChainPath(String industry, String name) {
    if (name.contains("精智达") || name.contains("华峰") || name.contains("长川")) {
      return "AI/HBM需求 → 存储原厂(三星/海力士/长江存储/长鑫) → 测试设备供应商";
    }
    if (name.contains("中微") || name.contains("北方华创")) {
      return "AI/HBM需求 → 晶圆厂 → 刻蚀/沉积设备";
    }
    return "需结合行业上下游分析";
  }

  private String inferMoatType(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("C35")) {
      return "地缘保护型(出口管制+国产替代政策) + 技术壁垒(高端设备研发周期3-5年)";
    }
    return "需结合个股分析";
  }

  private String inferCompetitors(String industry, String name) {
    if (name.contains("精智达")) return "爱德万(日本) / 泰瑞达(美国) / 精智达(国内唯一)";
    if (name.contains("华峰")) return "泰瑞达(美国) / 爱德万(日本) / 华峰测控(国内领先)";
    if (name.contains("长川")) return "爱德万 / 泰瑞达 / 长川科技 / 分选机其他玩家";
    return "需结合行业研究";
  }

  private String inferChinesePosition(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("C35")) {
      return "国产替代核心受益方, 但高端产品仍由外资主导";
    }
    return "需结合行业格局";
  }

  private String inferGeoAdvantage(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("C35")) {
      return "美对华14nm以下设备出口管制 → 国产替代窗口期3-5年";
    }
    return "需结合地缘政治分析";
  }

  private int calcMoat(String industry, String name) {
    int score = 5;
    if (industry.contains("半导体") || industry.contains("C35")) score += 3;
    if (name.contains("精智达") || name.contains("华峰")) score += 1;
    if (name.contains("唯一") || name.contains("稀缺")) score += 1;
    return Math.min(10, score);
  }

  // ============================================================
  // 工具
  // ============================================================
  private String normalizeCode(String code) {
    if (code == null) return "";
    code = code.trim().toLowerCase();
    if (code.contains(".")) return code;
    if (code.matches("\\d{6}")) {
      if (code.startsWith("60") || code.startsWith("68") || code.startsWith("90"))
        return "sh." + code;
      if (code.startsWith("00") || code.startsWith("30") || code.startsWith("20"))
        return "sz." + code;
      if (code.startsWith("43")
          || code.startsWith("83")
          || code.startsWith("87")
          || code.startsWith("88")) return "bj." + code;
    }
    return code;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object o) {
    if (o instanceof Map) return (Map<String, Object>) o;
    return Collections.emptyMap();
  }

  @SuppressWarnings("unchecked")
  private List<Object> asList(Object o) {
    if (o == null) return Collections.emptyList();
    if (o instanceof List) return (List<Object>) o;
    return Collections.emptyList();
  }

  private Double parseDouble(Object o) {
    if (o == null) return null;
    if (o instanceof Number) return ((Number) o).doubleValue();
    try {
      return Double.parseDouble(String.valueOf(o));
    } catch (Exception e) {
      return null;
    }
  }

  private String formatPct(Object o) {
    Double d = parseDouble(o);
    if (d == null) return "N/A";
    return String.format("%.2f%%", d * 100);
  }

  private String safe(Object v) {
    return v == null ? "" : v.toString();
  }

  private static final String SYSTEM_PROMPT =
      "你是一名资深的 A 股价值景气投资分析师，擅长从全球产业趋势、行业周期、国家政策、"
          + "公司基本面、管理层、估值、技术面、资金面进行全维度分析。"
          + "请严格按照用户给出的 JSON Schema 输出，不要使用 markdown，"
          + "不要输出任何解释或前后多余文字，输出必须是合法的 JSON。";

  private static final String FIVE_DIM_SYSTEM_PROMPT =
      "你是一名资深的 A 股产业研究与成长股估值分析师，专注用「五维模型 + 市值阶梯式增长路径」"
          + "拆解公司的中长期投资价值。五维模型分别是：稀缺卡位、成长动力、业绩兑现、瓶颈壁垒、估值阶梯。"
          + "你的核心方法论是：(1) 优先用联网检索获得的产业链证据作支撑，引用时要给出依据；"
          + "(2) 检索不到的深度数据（设备数量、产能爬坡、订单排期等），明确写\"未检索到，待人工核实\"，**绝不编造具体数字**；"
          + "(3) 每个维度先给定量数据，再给定性结论；(4) 估值阶梯用 PE+PS 双体系交叉验证。"
          + "请严格按照用户给出的 JSON Schema 输出，不要使用 markdown，不要输出任何解释或前后多余文字，输出必须是合法的 JSON。";

  private static final String JSON_SCHEMA =
      """
            {
              "industry": {
                "cyclePosition": "上行/下行 + 描述当前所处位置",
                "lastCycleReview": "上一轮完整周期时长、顶底特征以及对比当前位置",
                "next12mForecast": "未来12个月拐点核心触发条件、向上/向下概率与弹性",
                "entryBarrier": "高/中/低，并说明新进入者难易度与现有竞争者增减情况",
                "lifeStage": "导入期/成长期/成熟期/萎缩期",
                "competition": "CR5 市场份额数据 + 公司行业地位",
                "globalResonance": "主要国家共振程度与政策支持度"
              },
              "company": {
                "businessMix": "各业务线及其营收占比，新增长曲线",
                "quarterly12": "近12季度营收/归母/扣非净利润同比环比 + 驱动因子拆分",
                "next2yDriver": "未来2年业绩驱动因素",
                "moat": "护城河，可持续性与被颠覆风险",
                "policyFit": "是否国家重点扶持，与十五五规划相关度",
                "globalization": "海外营收过去3年占比走势",
                "priceTrend": "过去1年产品/服务价格变化以及未来1年走势",
                "chairman": "董事长年龄/学历/经历/专业度/企业家精神",
                "catalysts": "概念、故事、股价催化剂"
              },
              "valuation": {
                "type": "成长型/强周期/成熟稳定/亏损或周期底部",
                "methods": [
                  {"name":"PEG/PE/PB/PS/EV-EBITDA/DCF/股息率等","current":"当前值","reasonable":"合理区间","verdict":"便宜/合理/略贵/泡沫"}
                ],
                "target2026": "目标价",
                "target2027": "目标价",
                "verdict": "综合结论",
                "reasoning": "估值依据"
              },
              "technical": {
                "trendLine": "趋势线判断",
                "ma": "均线判断",
                "volume": "量价关系",
                "macd": "MACD判断",
                "verdict": "综合结论"
              },
              "capital": {
                "mainNetIn": "主力资金情况",
                "northbound": "北向资金情况",
                "dragonTiger": "龙虎榜情况",
                "verdict": "综合结论"
              },
              "summary": {
                "bullets": ["最多6条要点"],
                "oneLiner": "一句话结论"
              }
            }
            """;

  private static final String FIVE_DIM_JSON_SCHEMA =
      """
            {
              "稀缺卡位": {
                "rating": "X星/4星半/4星 (满分5星)",
                "ratingLogic": "为什么是这个评级 (1-2句)",
                "全球技术稀缺性": {
                  "全球可量产玩家数": "数字 + 玩家名 (检索不到写'未检索到，待人工核实')",
                  "公司在A股的稀缺性": "全A股唯一/国产替代核心/...",
                  "关键技术指标": "工艺/精度/规格 + 行业标准对照",
                  "国内同业技术代差": "X-Y 年",
                  "研发投入": "金额 + 占营收比 + 累计专利数",
                  "卡位赛道": "AI算力/高端制造/卡脖子/..."
                },
                "双赛道卡位": {
                  "主业": {"客户/份额/认证周期": "..."},
                  "第二曲线": {"海内外客户矩阵": "..."},
                  "跨行业意义": "周期对冲/估值切换/...",
                  "业务结构演变": "旧业务占比X% → 新业务占比Y% 的爬坡轨迹"
                }
              },
              "成长动力": {
                "rating": "X星/4星半 (满分5星)",
                "ratingLogic": "...",
                "第一曲线": {
                  "业务名": "...",
                  "行业逻辑": "需求端驱动 + 客户产品周期",
                  "年化复合增速": "X%-Y%",
                  "稳态年度营收区间": "XX 亿 - XX 亿元",
                  "未来3年量化预测": {
                    "2026": "XX 亿元",
                    "2027": "XX 亿元",
                    "2028": "XX 亿元"
                  },
                  "角色定位": "托底利润与现金流，对冲第二曲线扩产期资本开支"
                },
                "第二曲线": {
                  "业务名": "...",
                  "行业需求端": "市场规模从当前X提升至Y，增幅Z%，高景气周期延续至YYYY年",
                  "产能端": "现有产线状态 + 新建基地(规划产能 + 投产爬坡时点)",
                  "客户端": "海外大厂订单 + 国内供应链导入 + 订单排期延伸至XXXX",
                  "未来3年量化预测": {
                    "2026": "第二曲线收入XX亿元，同比X%；公司总营收XX亿元，同比X%；第二曲线占比X%",
                    "2027": "第二曲线收入XX亿元，同比X%；公司总营收XX亿元，同比X%；第二曲线占比X%",
                    "2028": "第二曲线收入XX亿元，同比X%；公司总营收XX亿元，同比X%；第二曲线占比X%"
                  },
                  "关键里程碑": "第二曲线XXXX年正式超越第一曲线成为第一主业"
                }
              },
              "业绩兑现度": {
                "rating": "X星/4星半 (满分5星)",
                "ratingLogic": "...",
                "历史财报验证": {
                  "年份": "YYYY",
                  "总营收": "XX亿元, 同比±X%",
                  "归母净利润": "XX亿元, 同比±X%",
                  "经营活动现金流净额": "XX亿元, 同比±X% (重点关注由负转正的拐点)",
                  "业务毛利率结构": "业务A X% / 业务B X% (重点看高毛利业务占比是否抬升)"
                },
                "当期财报验证": {
                  "季度": "YYYY QX",
                  "营收": "XX亿元, 同比X%",
                  "归母净利润": "XX亿元, 同比X%",
                  "扣非净利润": "XX亿元, 同比X%",
                  "核心信号": "利润增速 > 营收增速 → 验证高毛利业务放量 + 规模效应 (或反之)"
                },
                "远期利润与毛利率预判": [
                  {"年份": "2026E", "归母净利润": "X.X亿元", "综合毛利率": "XX%", "核心兑现逻辑": "产能爬坡 + 大客户订单 + ..."},
                  {"年份": "2027E", "归母净利润": "X.X亿元", "综合毛利率": "XX%", "核心兑现逻辑": "满产 + 订单放量 + ..."},
                  {"年份": "2028E", "归母净利润": "X.X亿元", "综合毛利率": "XX%", "核心兑现逻辑": "业务结构跃迁 + 国产替代 + ..."}
                ],
                "业绩兑现确定性": "高/中/低",
                "唯一变量": "产能爬坡节奏 / 海外交付 / 客户验收 / ..."
              },
              "瓶颈与壁垒": {
                "rating": "X星/4星 (满分5星)",
                "ratingLogic": "...",
                "核心护城河壁垒": [
                  {"类型": "技术专利壁垒", "数据": "核心IP来源(自研/并购) + 关键工艺指标 + 复刻周期"},
                  {"类型": "顶级客户认证壁垒", "数据": "客户名单 + 认证周期X-Y年 + 转换成本/排期粘性"},
                  {"类型": "重资产产能壁垒", "数据": "累计资本开支 + 产线/技工培养周期 + 新入局者门槛"}
                ],
                "当前成长约束瓶颈": [
                  {"类型": "客户结构瓶颈", "数据": "单一客户占比X%，受行业周期扰动"},
                  {"类型": "产能爬坡瓶颈", "数据": "新基地释放节奏 + 海外大客户交付进度"},
                  {"类型": "地缘外部瓶颈", "数据": "海外供应链政策不确定性"}
                ]
              },
              "估值阶梯": {
                "估值底层逻辑": "当前市值仅充分定价主业价值；高成长业务估值未完全计价 + 估值中枢切换逻辑",
                "估值体系": "主业PE X-Y倍；高成长业务PE X-Y倍 / PS X-Y倍",
                "第一阶梯": {
                  "时间窗口": "YYYY年底/半年内",
                  "预期归母净利润": "X.X亿元",
                  "预期总营收": "XX亿元",
                  "估值中枢": "X-Y倍PE / X-Y倍PS",
                  "目标市值区间": "XXX - XXX 亿元",
                  "每股目标价": "XX - XX 元",
                  "核心上涨催化": "..."
                },
                "第二阶梯": {
                  "时间窗口": "YYYY年底/1-1.5年",
                  "预期归母净利润": "X.X亿元",
                  "预期总营收": "XX亿元",
                  "第二曲线营收占比": "X%",
                  "估值中枢": "X-Y倍PE / X-Y倍PS",
                  "目标市值区间": "XXX - XXX 亿元",
                  "每股目标价": "XX - XX 元",
                  "核心上涨催化": "..."
                },
                "第三阶梯": {
                  "时间窗口": "YYYY年底/3年长线",
                  "预期归母净利润": "X.X亿元",
                  "预期总营收": "XX亿元",
                  "第二曲线地位": "正式成为第一主业",
                  "稳态PE/PS": "XX倍PE / X倍PS",
                  "PE测算稳态市值": "XX亿元",
                  "PS测算稳态市值": "XXX亿元",
                  "每股目标价": "XXX - XXX 元",
                  "核心逻辑": "从A行业龙头 → B行业核心龙头 身份蜕变 + 估值体系重构"
                },
                "风险提示": [
                  "[主业]客户资本开支不及预期，[业务A]订单下滑拖累基本盘",
                  "[行业]整体产能扩张速度低于预期，下游设备采购需求疲软",
                  "海外大客户认证/交付进度滞后，[业务B]放量速度不及预期"
                ]
              },
              "summary": {
                "verdict": "盯住/就是它了/观望/回避",
                "oneLiner": "一句话总结(包含评级和核心逻辑)",
                "coreDrivers": ["驱动1", "驱动2", "驱动3"]
              }
            }
            """;
}
