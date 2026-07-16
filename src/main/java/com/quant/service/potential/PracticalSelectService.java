package com.quant.service.potential;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.dto.practicalselect.FinancialAnalysis;
import com.quant.dto.practicalselect.PracticalSelectResponse;
import com.quant.dto.practicalselect.StarRating;
import com.quant.dto.practicalselect.TrendAnalysis;
import com.quant.dto.practicalselect.ValuationAnalysis;
import com.quant.entity.InvestPracticalSelectRecord;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestPracticalSelectRecordRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;
import com.quant.service.practical.PracticalFinancialAnalyzer;
import com.quant.service.practical.PracticalRatingAnalyzer;
import com.quant.service.practical.PracticalTrendAnalyzer;
import com.quant.service.practical.PracticalValuationAnalyzer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 实战选股 · 综合分析（门面）。
 *
 * <p>输入：股票代码或名称 输出：走势 + 财务 + 星级 + 估值 一站式分析。 具体计算委托给 {@code service/practical/} 下的分析器： 走势 {@link
 * PracticalTrendAnalyzer}、财务 {@link PracticalFinancialAnalyzer}、估值 {@link
 * PracticalValuationAnalyzer}、评级 {@link PracticalRatingAnalyzer}。 本类负责编排、缓存复用、历史持久化与公开分享。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PracticalSelectService {

  /** 同一 keyword 在 24 小时内复用上次的分析结果（避免重复 AI 调用）。 */
  private static final long CACHE_REUSE_MINUTES = 24 * 60;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final TradeStockBasicRepository stockBasicRepository;
  private final InvestPracticalSelectRecordRepository recordRepository;
  private final AStockDataQuoteService quoteService;
  private final PracticalTrendAnalyzer trendAnalyzer;
  private final PracticalFinancialAnalyzer financialAnalyzer;
  private final PracticalValuationAnalyzer valuationAnalyzer;
  private final PracticalRatingAnalyzer ratingAnalyzer;

  /** 综合分析入口（带持久化历史记录 + 24 小时复用）。 返回的对象是最新结果；持久化是副作用。 */
  public PracticalSelectResponse analyze(String keyword) {
    return analyzeInternal(keyword, true);
  }

  /** 不写入历史记录的分析（用于详情接口预览等场景）。 */
  public PracticalSelectResponse analyzeWithoutHistory(String keyword) {
    return analyzeInternal(keyword, false);
  }

  private PracticalSelectResponse analyzeInternal(String keyword, boolean saveHistory) {
    String kw = keyword == null ? "" : keyword.trim();
    if (kw.isEmpty()) {
      return PracticalSelectResponse.builder().matched(false).message("请输入股票代码或名称").build();
    }

    Optional<TradeStockBasic> basicOpt = resolveStock(kw);
    if (basicOpt.isEmpty()) {
      return PracticalSelectResponse.builder()
          .matched(false)
          .message("未找到该股票：" + kw + "（请输入 6 位代码或完整名称）")
          .build();
    }
    TradeStockBasic basic = basicOpt.get();
    String code = basic.getStockCode();

    // 0) 24 小时内同 keyword 复用最近一条记录（避免重复 AI 调用）
    if (saveHistory) {
      Optional<InvestPracticalSelectRecord> recent =
          recordRepository.findAllByOrderByIdDesc(PageRequest.of(0, 20)).getContent().stream()
              .filter(r -> kw.equalsIgnoreCase(r.getKeyword()) || code.equals(r.getStockCode()))
              .filter(
                  r ->
                      r.getCreatedAt() != null
                          && r.getCreatedAt()
                              .isAfter(
                                  java.time.LocalDateTime.now().minusMinutes(CACHE_REUSE_MINUTES)))
              .findFirst();
      if (recent.isPresent()) {
        PracticalSelectResponse cached = parseRecord(recent.get());
        if (cached != null) {
          log.info(
              "命中 {} 分钟内缓存: keyword={}, recordId={}",
              CACHE_REUSE_MINUTES,
              kw,
              recent.get().getId());
          return cached;
        }
      }
    }

    long startMs = System.currentTimeMillis();

    // 1) 实时行情 + 当前市值
    AStockDataQuoteService.QuoteSnapshot quote =
        quoteService.fetchQuotes(List.of(code)).values().stream().findFirst().orElse(null);
    Double currentPrice =
        quote != null && quote.latestPrice() != null ? quote.latestPrice().doubleValue() : null;

    // 2) 走势分析
    TrendAnalysis trend = trendAnalyzer.buildTrend(code);

    // 3) 财务分析 + SOP 体检
    FinancialAnalysis financials = financialAnalyzer.buildFinancials(code);

    // 4) 估值分析（本地）
    ValuationAnalysis valuation = valuationAnalyzer.buildValuation(basic, quote, financials);

    // 5) 星级评级（LLM，带 fallback）
    StarRating rating = ratingAnalyzer.buildRating(basic, financials, valuation, trend);

    // 6) 综合标题
    String headline = buildHeadline(basic, trend, financials, valuation);

    int dataDays = trend.getDataDays();
    String dataNote =
        String.format(
            "数据：财务 %d 季度（%s ~ %s）；日 K %d 个交易日（%s ~ %s）；行情 %s。",
            financials.getQuarters() != null ? financials.getQuarters().size() : 0,
            financials.getQuarters() != null && !financials.getQuarters().isEmpty()
                ? financials.getQuarters().get(0).getReportDate()
                : "—",
            financials.getQuarters() != null && !financials.getQuarters().isEmpty()
                ? financials.getQuarters().get(financials.getQuarters().size() - 1).getReportDate()
                : "—",
            dataDays,
            trend.getDataStartDate() == null ? "—" : trend.getDataStartDate(),
            trend.getDataEndDate() == null ? "—" : trend.getDataEndDate(),
            quote != null ? "已获取" : "缺失，部分指标用基础数据估算");

    PracticalSelectResponse resp =
        PracticalSelectResponse.builder()
            .matched(true)
            .stockCode(code)
            .stockName(basic.getStockName())
            .currentPrice(currentPrice)
            .trend(trend)
            .financials(financials)
            .rating(rating)
            .valuation(valuation)
            .summaryHeadline(headline)
            .dataNote(dataNote)
            .build();

    long elapsedMs = System.currentTimeMillis() - startMs;

    // 7) 写入历史
    if (saveHistory) {
      try {
        saveRecord(kw, basic, resp, valuation == null ? null : valuation.getVerdict(), elapsedMs);
      } catch (Exception e) {
        log.warn("保存历史记录失败: {}", e.getMessage());
      }
    }
    return resp;
  }

  @Transactional
  protected void saveRecord(
      String keyword,
      TradeStockBasic basic,
      PracticalSelectResponse resp,
      String verdict,
      long elapsedMs) {
    InvestPracticalSelectRecord rec = new InvestPracticalSelectRecord();
    rec.setStockCode(resp.getStockCode() == null ? basic.getStockCode() : resp.getStockCode());
    rec.setStockName(resp.getStockName() == null ? basic.getStockName() : resp.getStockName());
    rec.setKeyword(keyword);
    rec.setStatus("SUCCESS");
    rec.setHeadline(resp.getSummaryHeadline());
    rec.setVerdict(verdict);
    rec.setElapsedMs(elapsedMs);
    try {
      rec.setResultJson(MAPPER.writeValueAsString(resp));
    } catch (JsonProcessingException e) {
      log.warn("序列化响应失败: {}", e.getMessage());
    }
    InvestPracticalSelectRecord saved = recordRepository.save(rec);
    // 把 recordId 回填到响应
    resp.setRecordId(saved.getId());
  }

  // ============================================================
  // 历史记录管理
  // ============================================================

  public Page<InvestPracticalSelectRecord> listRecords(String kw, int page, int size) {
    Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
    if (kw == null || kw.isBlank()) {
      return recordRepository.findAllByOrderByIdDesc(pageable);
    }
    return recordRepository.search(kw.trim(), pageable);
  }

  public PracticalSelectResponse getRecordResponse(Long id) {
    InvestPracticalSelectRecord rec =
        recordRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("记录不存在：" + id));
    return parseRecord(rec);
  }

  private PracticalSelectResponse parseRecord(InvestPracticalSelectRecord rec) {
    if (rec.getResultJson() == null || rec.getResultJson().isBlank()) {
      return null;
    }
    try {
      return MAPPER.readValue(rec.getResultJson(), PracticalSelectResponse.class);
    } catch (Exception e) {
      log.warn("解析 record {} resultJson 失败: {}", rec.getId(), e.getMessage());
      return null;
    }
  }

  @Transactional
  public void deleteRecord(Long id) {
    if (!recordRepository.existsById(id)) {
      throw new IllegalArgumentException("记录不存在：" + id);
    }
    recordRepository.deleteById(id);
  }

  /** 启用公开分享，返回完整分享 URL。 */
  @Transactional
  public String enableShare(Long id, String baseUrl) {
    InvestPracticalSelectRecord rec =
        recordRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("记录不存在：" + id));
    if (rec.getShareToken() == null || rec.getShareToken().isBlank()) {
      rec.setShareToken(UUID.randomUUID().toString().replace("-", ""));
    }
    rec.setIsPublic(1);
    recordRepository.save(rec);
    String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    return base + "/gp/share.html?t=" + rec.getShareToken();
  }

  @Transactional
  public void disableShare(Long id) {
    InvestPracticalSelectRecord rec =
        recordRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("记录不存在：" + id));
    rec.setIsPublic(0);
    // token 保留，便于将来再次启用；若要彻底作废可同时置 null
    recordRepository.save(rec);
  }

  /** 公开分享访问（无需鉴权）。 */
  public PracticalSelectResponse getShared(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("缺少 token");
    }
    InvestPracticalSelectRecord rec =
        recordRepository
            .findByShareToken(token)
            .orElseThrow(() -> new IllegalArgumentException("分享链接无效或已过期"));
    if (rec.getIsPublic() == null || rec.getIsPublic() != 1) {
      throw new IllegalStateException("该分享已关闭");
    }
    return parseRecord(rec);
  }

  // ============ 综合标题 ============

  private String buildHeadline(
      TradeStockBasic basic, TrendAnalysis trend, FinancialAnalysis fin, ValuationAnalysis val) {
    StringBuilder sb = new StringBuilder();
    sb.append(basic.getStockName()).append("（").append(basic.getStockCode()).append("）");
    if (val != null && val.getVerdict() != null) {
      sb.append(" · ").append(val.getVerdict());
    }
    if (trend != null && trend.getMonthToDateReturnPct() != null) {
      sb.append(String.format(Locale.ROOT, " · 本月%+,.2f%%", trend.getMonthToDateReturnPct()));
    }
    return sb.toString();
  }

  // ============ 工具方法 ============

  private Optional<TradeStockBasic> resolveStock(String token) {
    String t = token.trim();
    if (t.isEmpty()) return Optional.empty();
    String bareCode = t.contains(".") ? t.substring(0, t.indexOf('.')) : t;

    if (bareCode.matches("\\d{4,8}")) {
      Optional<TradeStockBasic> byFull = stockBasicRepository.findByStockCode(t);
      if (byFull.isPresent()) return byFull;
      Optional<TradeStockBasic> byExact = stockBasicRepository.findByStockCode(bareCode);
      if (byExact.isPresent()) return byExact;
      List<TradeStockBasic> byPrefix = stockBasicRepository.findByStockCodePrefix(bareCode);
      if (!byPrefix.isEmpty()) return Optional.of(byPrefix.get(0));
    }
    List<TradeStockBasic> byName = stockBasicRepository.findByStockNameLike(t);
    if (!byName.isEmpty()) return Optional.of(byName.get(0));
    return Optional.empty();
  }
}
