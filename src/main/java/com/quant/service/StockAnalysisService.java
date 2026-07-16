package com.quant.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.quant.dto.stockanalysis.StockAnalysisRecordListDTO;
import com.quant.dto.stockanalysis.StockAnalysisRequest;
import com.quant.dto.stockanalysis.StockAnalysisResponse;
import com.quant.dto.stockanalysis.WindResearchContext;
import com.quant.entity.StockAnalysisRecord;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.StockAnalysisRecordRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.service.stockanalysis.AnalysisAiCaller;
import com.quant.service.stockanalysis.AnalysisPromptBuilder;
import com.quant.service.stockanalysis.BaostockDataFetcher;
import com.quant.service.stockanalysis.NineDimensionComposer;
import com.quant.service.stockanalysis.WindResearchService;
import com.quant.service.potential.UnifiedStockResearchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 个股分析服务 facade (异步版).
 *
 * <p>submit: 创建 PENDING 记录, 立即返回 id. executeAsync: 后台跑 baostock + 紫苏叶/九维, 写回 DB. getById / list:
 * 查询记录.
 *
 * <p>缓存策略: 同 code + method 的 SUCCESS 记录 1 小时内直接复用.
 *
 * <p>职责拆分 (Sprint 2.4 Task 4): data fetch → BaostockDataFetcher; prompt build →
 * AnalysisPromptBuilder; AI call + JSON parse → AnalysisAiCaller; data shaping utils →
 * NineDimensionComposer; industry inference → IndustryRulesInference (see stockanalysis/ package).
 * 紫苏叶/九维段落组装由 {@link UnifiedStockResearchService} 完成, 本 facade 不再重复实现.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAnalysisService {

  /** 缓存有效期 (小时) */
  private static final int CACHE_HOURS = 1;

  private final StockAnalysisRecordRepository repository;
  private final StockQueryService stockQueryService;
  private final TradeStockBasicRepository stockBasicRepository;
  private final UnifiedStockResearchService unifiedStockResearchService;
  private final WindResearchService windResearchService;
  private final BaostockDataFetcher baostockFetcher;
  private final AnalysisPromptBuilder promptBuilder;
  private final AnalysisAiCaller aiCaller;
  private final NineDimensionComposer util;
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
    rec.setStatus("RUNNING");
    rec.setStartedAt(LocalDateTime.now());
    rec = repository.save(rec);

    long start = System.currentTimeMillis();
    try {
      StockAnalysisResponse resp = doAnalyze(toRequest(rec));
      long elapsed = System.currentTimeMillis() - start;
      applySuccess(rec, resp, elapsed);
      log.info("分析完成: id={} code={} elapsed={}ms", recordId, rec.getStockCode(), elapsed);
    } catch (Exception e) {
      log.error("分析失败: id={}", recordId, e);
      applyFailure(rec, e, System.currentTimeMillis() - start);
    }
  }

  private StockAnalysisRequest toRequest(StockAnalysisRecord rec) {
    StockAnalysisRequest req = new StockAnalysisRequest();
    req.setCode(rec.getStockCodeRaw());
    req.setMethod(rec.getMethod());
    req.setYears(rec.getYears());
    req.setLite(rec.getLite() == 1);
    req.setQuoteDays(rec.getQuoteDays());
    return req;
  }

  private void applySuccess(StockAnalysisRecord rec, StockAnalysisResponse resp, long elapsedMs) {
    rec.setStatus("SUCCESS");
    rec.setFinishedAt(LocalDateTime.now());
    rec.setElapsedMs((int) elapsedMs);
    if (resp != null) {
      rec.setStockName(resp.getName());
      rec.setCurrentPrice(
          resp.getCurrentPrice() == null ? null : BigDecimal.valueOf(resp.getCurrentPrice()));
      rec.setVerdict(resp.getVerdict());
      rec.setMoatScore(resp.getMoatScore());
      rec.setResultJson(serializeResult(resp));
      rec.setReportHtml(resp.getReportHtml());
      rec.setSourcePayloadJson(serializeSourcePayload(resp));
    }
    repository.save(rec);
  }

  private void applyFailure(StockAnalysisRecord rec, Exception e, long elapsedMs) {
    rec.setStatus("FAILED");
    rec.setFinishedAt(LocalDateTime.now());
    rec.setElapsedMs((int) elapsedMs);
    rec.setErrorMessage(e.getMessage() == null ? e.getClass().getName() : e.getMessage());
    repository.save(rec);
  }

  private String serializeResult(StockAnalysisResponse resp) {
    try {
      return objectMapper.writeValueAsString(resp);
    } catch (Exception e) {
      log.warn("序列化 result 失败: {}", e.getMessage());
      return "{}";
    }
  }

  private String serializeSourcePayload(StockAnalysisResponse resp) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "sourceMetadata",
                  resp.getSourceMetadata() == null
                      ? Collections.emptyMap()
                      : resp.getSourceMetadata(),
              "rawData", resp.getRawData() == null ? Collections.emptyMap() : resp.getRawData()));
    } catch (Exception e) {
      log.warn("序列化 sourcePayload 失败: {}", e.getMessage());
      return "{}";
    }
  }

  // ============================================================
  // 3. 同步版 (供 executeAsync 内部调用, 也可被外部直接调)
  // ============================================================
  public StockAnalysisResponse doAnalyze(StockAnalysisRequest req) {
    String code = unifiedStockResearchService.normalizeCode(req.getCode());
    String method = req.getMethod() == null ? "full" : req.getMethod();

    Map<String, Object> rawData = baostockFetcher.fetchPack(code, req);
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
                      String.valueOf(
                          util.asMap(rawData.get("basic")).getOrDefault("code_name", code)));
                  return synthetic;
                });
    WindResearchContext windResearch =
        windResearchService.fetch(code, basic.getStockName(), method);
    String prompt = promptBuilder.buildPrompt(basic, rawData, method, windResearch);
    Map<String, Object> aiAnalysis =
        aiCaller.analyze(promptBuilder.pickSystemPrompt(method), prompt);
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
    if (t.contains(".")) return true;
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
                : util.asMap(response.getAnalysis().get("summary"));
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
      if (realName != null && !realName.isBlank()) resolvedName = realName;
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
      if (meta instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("available"))) count++;
    }
    return count;
  }
}
