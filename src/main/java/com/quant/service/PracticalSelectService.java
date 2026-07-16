package com.quant.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.config.AiProperties;
import com.quant.dto.invest.SopCheckupDTO;
import com.quant.dto.practicalselect.FinancialAnalysis;
import com.quant.dto.practicalselect.PracticalSelectResponse;
import com.quant.dto.practicalselect.StarRating;
import com.quant.dto.practicalselect.TrendAnalysis;
import com.quant.dto.practicalselect.ValuationAnalysis;
import com.quant.entity.InvestPracticalSelectRecord;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.InvestPracticalSelectRecordRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.ai.MiniMaxClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 实战选股 · 综合分析。
 *
 * <p>输入：股票代码或名称 输出：走势 + 财务 + 星级 + 估值 一站式分析
 *
 * <p>数据流： 1. 解析股票基础信息（DB） 2. 拉 16 季度财务（DB）+ 复用 SOP 体检 3. 拉日 K（DB）+ 本地聚合成月 K + 突破判定 4.
 * 拉实时行情（a-stock-data）+ 算市值 5. 算 PS 估值（基于最新净利率） 6. 调 MiniMax 生成稀缺性 + 成长动力星级（带 fallback）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PracticalSelectService {

  private static final int QUARTER_COUNT = 16; // 16 季度财务数据
  private static final int MONTHLY_LOOKBACK = 24; // 月线分析最长往前看 24 个月
  private static final double BIG_YANG_THRESHOLD = 9.5; // 大阳线阈值（涨幅 ≥ 9.5%）
  private static final double BREAKOUT_LOOKBACK_MONTHS = 6;
  private static final double BREAKOUT_THRESHOLD_PCT = 3.0;

  /** 同一 keyword 在 24 小时内复用上次的分析结果（避免重复 AI 调用）。 */
  private static final long CACHE_REUSE_MINUTES = 24 * 60;

  private final TradeStockBasicRepository stockBasicRepository;
  private final TradeStockFinancialRepository financialRepository;
  private final TradeStockDailyRepository dailyRepository;
  private final InvestPracticalSelectRecordRepository recordRepository;
  private final InvestService investService;
  private final AStockDataQuoteService quoteService;
  private final MiniMaxClient miniMaxClient;
  private final com.quant.service.ai.SenseNovaClient senseNovaClient;
  private final AiProperties aiProperties;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

  /** 综合分析入口（带持久化历史记录 + 30 分钟复用）。 返回的对象是最新结果；持久化是副作用。 */
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

    // 0) 30 分钟内同 keyword 复用最近一条记录（避免重复 AI 调用）
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
    TrendAnalysis trend = buildTrend(code);

    // 3) 财务分析 + SOP 体检
    FinancialAnalysis financials = buildFinancials(code);

    // 4) 估值分析（本地）
    ValuationAnalysis valuation = buildValuation(basic, quote, financials);

    // 5) 星级评级（LLM，带 fallback）
    StarRating rating = buildRating(basic, financials, valuation, trend);

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

  // ============ 走势分析 ============

  private TrendAnalysis buildTrend(String code) {
    LocalDate to = LocalDate.now();
    LocalDate from = to.minusMonths(MONTHLY_LOOKBACK);
    List<TradeStockDaily> dailies =
        dailyRepository.findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(code, from, to);

    if (dailies.isEmpty()) {
      return TrendAnalysis.builder()
          .summary("暂无日 K 数据，无法分析走势")
          .monthlyBars(List.of())
          .recentBigYang(List.of())
          .build();
    }

    // 按月分组
    Map<YearMonth, List<TradeStockDaily>> grouped =
        dailies.stream()
            .collect(
                Collectors.groupingBy(
                    d -> YearMonth.from(d.getTradeDate()),
                    LinkedHashMap::new,
                    Collectors.toList()));

    List<TrendAnalysis.MonthlyBar> bars = new ArrayList<>();
    YearMonth prevMonth = null;
    Double prevMonthClose = null;
    for (Map.Entry<YearMonth, List<TradeStockDaily>> e : grouped.entrySet()) {
      List<TradeStockDaily> rows = e.getValue();
      rows.sort(Comparator.comparing(TradeStockDaily::getTradeDate));
      TradeStockDaily first = rows.get(0);
      TradeStockDaily last = rows.get(rows.size() - 1);
      double high = rows.stream().mapToDouble(r -> nullSafe(r.getHighPrice())).max().orElse(0);
      double low = rows.stream().mapToDouble(r -> nullSafe(r.getLowPrice())).min().orElse(0);
      long volume = rows.stream().mapToLong(r -> r.getVolume() == null ? 0L : r.getVolume()).sum();
      double monthClose = nullSafe(last.getClosePrice());
      double monthOpen = nullSafe(first.getOpenPrice());
      double returnPct = monthOpen > 0 ? round2((monthClose - monthOpen) / monthOpen * 100) : null;
      bars.add(
          TrendAnalysis.MonthlyBar.builder()
              .month(e.getKey().format(MONTH_FMT))
              .close(round2(monthClose))
              .high(round2(high))
              .low(round2(low))
              .volume(volume)
              .returnPct(returnPct)
              .build());
      prevMonth = e.getKey();
      prevMonthClose = monthClose;
    }

    // 本月至今涨幅 = 当月最新收盘 / 上月收盘 - 1
    Double monthToDateReturnPct = null;
    Double lastMonthReturnPct = null;
    if (bars.size() >= 2) {
      TrendAnalysis.MonthlyBar current = bars.get(bars.size() - 1);
      TrendAnalysis.MonthlyBar prev = bars.get(bars.size() - 2);
      if (prev.getClose() != null && prev.getClose() > 0 && current.getClose() != null) {
        monthToDateReturnPct =
            round2((current.getClose() - prev.getClose()) / prev.getClose() * 100);
      }
      lastMonthReturnPct = prev.getReturnPct();
    } else if (bars.size() == 1) {
      lastMonthReturnPct = bars.get(0).getReturnPct();
    }

    // 近 60 个交易日最大涨幅 / 回撤
    List<TradeStockDaily> last60 =
        dailies.subList(Math.max(0, dailies.size() - 60), dailies.size());
    Double maxGainPct = computeMaxGainPct(last60);
    Double maxDrawdownPct = computeMaxDrawdownPct(last60);

    // 突破平台判定：最近 1-2 月收盘价 > 之前 6 个月最高收盘价 × (1+3%)
    boolean breakout = false;
    String breakoutNote = null;
    if (bars.size() >= 8) {
      int n = bars.size();
      TrendAnalysis.MonthlyBar latest = bars.get(n - 1);
      double latestClose = latest.getClose() != null ? latest.getClose() : 0;
      double priorHigh =
          bars.subList(Math.max(0, n - 1 - (int) BREAKOUT_LOOKBACK_MONTHS), n - 1).stream()
              .filter(b -> b.getHigh() != null)
              .mapToDouble(TrendAnalysis.MonthlyBar::getHigh)
              .max()
              .orElse(0);
      if (priorHigh > 0 && latestClose > priorHigh * (1 + BREAKOUT_THRESHOLD_PCT / 100)) {
        breakout = true;
        breakoutNote =
            String.format(
                Locale.ROOT,
                "本月收盘 %.2f 元 > 近 %d 月最高 %.2f 元，确认向上突破平台",
                latestClose,
                (int) BREAKOUT_LOOKBACK_MONTHS,
                priorHigh);
      }
    }

    // 最近大阳线（涨幅 ≥ 9.5%，用前一交易日 close 计算）
    List<TrendAnalysis.BigYangLine> bigYang = new ArrayList<>();
    for (int i = dailies.size() - 1; i >= 1 && bigYang.size() < 10; i--) {
      TradeStockDaily d = dailies.get(i);
      TradeStockDaily prev = dailies.get(i - 1);
      double prevClose = nullSafe(prev.getClosePrice());
      double curClose = nullSafe(d.getClosePrice());
      if (prevClose > 0 && curClose > 0) {
        double pct = (curClose - prevClose) / prevClose * 100;
        if (pct >= BIG_YANG_THRESHOLD) {
          bigYang.add(
              TrendAnalysis.BigYangLine.builder()
                  .date(d.getTradeDate().toString())
                  .openPrice(round2(d.getOpenPrice()))
                  .closePrice(round2(d.getClosePrice()))
                  .highPrice(round2(d.getHighPrice()))
                  .pctChange(round2(pct))
                  .turnoverRate(
                      d.getTurnoverRate() != null
                          ? round2(d.getTurnoverRate().doubleValue())
                          : null)
                  .build());
        }
      }
    }

    // 文案
    String summary =
        buildTrendSummary(
            basicOptName(code), bars, breakout, monthToDateReturnPct, lastMonthReturnPct);

    return TrendAnalysis.builder()
        .summary(summary)
        .monthlyBars(bars)
        .monthToDateReturnPct(monthToDateReturnPct)
        .lastMonthReturnPct(lastMonthReturnPct)
        .sixtyDayMaxGainPct(maxGainPct)
        .sixtyDayMaxDrawdownPct(maxDrawdownPct)
        .breakoutDetected(breakout)
        .breakoutNote(breakoutNote)
        .recentBigYang(bigYang)
        .dataDays(dailies.size())
        .dataStartDate(dailies.get(0).getTradeDate().toString())
        .dataEndDate(dailies.get(dailies.size() - 1).getTradeDate().toString())
        .build();
  }

  private String buildTrendSummary(
      String name,
      List<TrendAnalysis.MonthlyBar> bars,
      boolean breakout,
      Double mtdReturn,
      Double lastReturn) {
    if (bars == null || bars.isEmpty()) {
      return "暂无走势数据";
    }
    if (breakout && mtdReturn != null && mtdReturn >= 20) {
      return String.format(
          Locale.ROOT,
          "%s走势很漂亮，刚刚确认向上突破近 %d 月的平台，本月至今涨幅 +%.2f%%，" + "配合近期大阳线，量价齐升。",
          name,
          (int) BREAKOUT_LOOKBACK_MONTHS,
          mtdReturn);
    }
    if (breakout) {
      return String.format(
          Locale.ROOT,
          "%s刚刚突破近 %d 月平台，本月至今 +%.2f%%，走势转强可关注。",
          name,
          (int) BREAKOUT_LOOKBACK_MONTHS,
          mtdReturn == null ? 0 : mtdReturn);
    }
    if (mtdReturn != null && mtdReturn >= 20) {
      return String.format(Locale.ROOT, "%s本月至今 +%.2f%%，短线强势但尚未确认突破前期平台，建议观察回踩。", name, mtdReturn);
    }
    if (lastReturn != null && lastReturn < -15) {
      return String.format(Locale.ROOT, "%s最近一月 %.2f%%，处于调整中，需等止跌信号。", name, lastReturn);
    }
    return String.format(
        Locale.ROOT,
        "%s本月至今 %+.2f%%，最近一月 %+.2f%%，走势中性。",
        name,
        mtdReturn == null ? 0 : mtdReturn,
        lastReturn == null ? 0 : lastReturn);
  }

  private double computeMaxGainPct(List<TradeStockDaily> rows) {
    if (rows.isEmpty()) return 0;
    double minSoFar = Double.MAX_VALUE;
    double maxGain = 0;
    for (TradeStockDaily d : rows) {
      double c = nullSafe(d.getClosePrice());
      if (c <= 0) continue;
      if (c < minSoFar) minSoFar = c;
      if (minSoFar > 0) {
        double gain = (c - minSoFar) / minSoFar * 100;
        if (gain > maxGain) maxGain = gain;
      }
    }
    return round2(maxGain);
  }

  private double computeMaxDrawdownPct(List<TradeStockDaily> rows) {
    if (rows.isEmpty()) return 0;
    double maxSoFar = 0;
    double maxDD = 0;
    for (TradeStockDaily d : rows) {
      double c = nullSafe(d.getClosePrice());
      if (c <= 0) continue;
      if (c > maxSoFar) maxSoFar = c;
      if (maxSoFar > 0) {
        double dd = (c - maxSoFar) / maxSoFar * 100;
        if (dd < maxDD) maxDD = dd;
      }
    }
    return round2(maxDD);
  }

  // ============ 财务分析 ============

  private FinancialAnalysis buildFinancials(String code) {
    List<TradeStockFinancial> all = financialRepository.findByStockCodeOrderByReportDateDesc(code);
    if (all.isEmpty()) {
      return FinancialAnalysis.builder()
          .summary("暂无该股票财务数据")
          .quarters(List.of())
          .sopVerdict("warn")
          .sopSummary("缺少财务数据，无法做 SOP 判定")
          .turnaroundDetected(false)
          .turnaroundNote("无数据")
          .build();
    }

    // 取最近 16 季度（按报告期升序）
    List<TradeStockFinancial> recent =
        new ArrayList<>(all.subList(0, Math.min(QUARTER_COUNT, all.size())));
    java.util.Collections.reverse(recent);

    List<FinancialAnalysis.QuarterSnapshot> snaps =
        recent.stream().map(this::toQuarterSnapshot).collect(Collectors.toList());

    // SOP 体检（复用现成实现），同时拆出三项明细给前端
    SopCheckupDTO sop = investService.sopCheckup(code);
    String sopVerdict = sop != null && sop.isMatched() ? sop.getOverallVerdict() : "warn";
    String sopSummary = sop != null && sop.isMatched() ? sop.getOverallSummary() : "缺少数据";
    List<FinancialAnalysis.SopMetricBrief> sopMetrics = new ArrayList<>();
    if (sop != null && sop.isMatched()) {
      if (sop.getGrossMargin() != null) sopMetrics.add(toSopBrief(sop.getGrossMargin()));
      if (sop.getRevenueYoy() != null) sopMetrics.add(toSopBrief(sop.getRevenueYoy()));
      if (sop.getProfitYoy() != null) sopMetrics.add(toSopBrief(sop.getProfitYoy()));
    }

    // 趋势序列（最近 8 季度）
    int n = Math.min(8, recent.size());
    List<Double> revYoy = new ArrayList<>();
    List<Double> profitYoy = new ArrayList<>();
    List<Double> gm = new ArrayList<>();
    for (int i = recent.size() - n; i < recent.size(); i++) {
      TradeStockFinancial f = recent.get(i);
      revYoy.add(f.getRevenueYoy() != null ? round2(f.getRevenueYoy().doubleValue()) : null);
      profitYoy.add(
          f.getDeductedNetProfitYoy() != null
              ? round2(f.getDeductedNetProfitYoy().doubleValue())
              : null);
      gm.add(f.getGrossMargin() != null ? round2(f.getGrossMargin().doubleValue()) : null);
    }

    // 最新一期
    TradeStockFinancial latest = recent.get(recent.size() - 1);
    Double latestGm =
        latest.getGrossMargin() != null ? round2(latest.getGrossMargin().doubleValue()) : null;
    Double latestNm =
        latest.getNetMargin() != null ? round2(latest.getNetMargin().doubleValue()) : null;
    Double latestRevYoy =
        latest.getRevenueYoy() != null ? round2(latest.getRevenueYoy().doubleValue()) : null;
    Double latestProfitYoy =
        latest.getDeductedNetProfitYoy() != null
            ? round2(latest.getDeductedNetProfitYoy().doubleValue())
            : null;

    // 业绩复苏判定：上一期营收同比 < 0 且最新一期 > 0
    boolean turnaround = false;
    String turnaroundNote = null;
    if (recent.size() >= 2) {
      TradeStockFinancial prev = recent.get(recent.size() - 2);
      BigDecimal prevYoy = prev.getRevenueYoy();
      if (prevYoy != null
          && prevYoy.doubleValue() < 0
          && latestRevYoy != null
          && latestRevYoy > 0) {
        turnaround = true;
        turnaroundNote =
            String.format(
                Locale.ROOT,
                "营收同比由上一期的 %.2f%% 转正为最新 +%.2f%%，业绩拐点确认",
                prevYoy.doubleValue(),
                latestRevYoy);
      }
    }

    String summary =
        buildFinancialsSummary(snaps, sopVerdict, turnaround, latestProfitYoy, latestRevYoy);

    return FinancialAnalysis.builder()
        .summary(summary)
        .quarters(snaps)
        .sopVerdict(sopVerdict)
        .sopSummary(sopSummary)
        .sopMetrics(sopMetrics)
        .revenueYoySeries(revYoy)
        .profitYoySeries(profitYoy)
        .grossMarginSeries(gm)
        .latestGrossMargin(latestGm)
        .latestNetMargin(latestNm)
        .latestRevenueYoy(latestRevYoy)
        .latestProfitYoy(latestProfitYoy)
        .turnaroundDetected(turnaround)
        .turnaroundNote(turnaroundNote)
        .build();
  }

  private FinancialAnalysis.SopMetricBrief toSopBrief(SopCheckupDTO.MetricCheck m) {
    String unit = m.getUnit() == null ? "" : m.getUnit();
    String latestText =
        m.getLatest() == null
            ? "—"
            : (m.getLatest().doubleValue() >= 0 ? "+" : "")
                + m.getLatest().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                + unit;
    return FinancialAnalysis.SopMetricBrief.builder()
        .label(m.getLabel())
        .verdict(m.getVerdict())
        .latestText(latestText)
        .tip(m.getTip())
        .build();
  }

  private FinancialAnalysis.QuarterSnapshot toQuarterSnapshot(TradeStockFinancial f) {
    // revenue 单位是元，转亿元
    Double revYi =
        f.getRevenue() != null ? round2(f.getRevenue().doubleValue() / 1_0000_0000) : null;
    return FinancialAnalysis.QuarterSnapshot.builder()
        .quarter(formatQuarter(f.getReportDate()))
        .reportDate(f.getReportDate().toString())
        .revenueYi(revYi)
        .revenueYoy(f.getRevenueYoy() != null ? round2(f.getRevenueYoy().doubleValue()) : null)
        .netMargin(f.getNetMargin() != null ? round2(f.getNetMargin().doubleValue()) : null)
        .grossMargin(f.getGrossMargin() != null ? round2(f.getGrossMargin().doubleValue()) : null)
        .eps(f.getEps() != null ? round2(f.getEps().doubleValue()) : null)
        .roe(f.getRoe() != null ? round2(f.getRoe().doubleValue()) : null)
        .build();
  }

  private String buildFinancialsSummary(
      List<FinancialAnalysis.QuarterSnapshot> snaps,
      String sopVerdict,
      boolean turnaround,
      Double profitYoy,
      Double revYoy) {
    StringBuilder sb = new StringBuilder();
    sb.append("最近 ").append(snaps.size()).append(" 个季度财务数据：");
    if (turnaround) {
      sb.append("营收同比刚刚转正");
    } else if (revYoy != null && revYoy > 0) {
      sb.append(String.format(Locale.ROOT, "最近营收 +%.2f%%", revYoy));
    } else if (revYoy != null) {
      sb.append(String.format(Locale.ROOT, "最近营收 %.2f%%", revYoy));
    }
    if (profitYoy != null && revYoy != null) {
      double diff = profitYoy - revYoy;
      sb.append(String.format(Locale.ROOT, "；扣非同比 %+.2f%%，与营收增速差 %+.2f%%。", profitYoy, diff));
    } else {
      sb.append("。");
    }
    sb.append("SOP 体检：");
    switch (sopVerdict) {
      case "pass" -> sb.append("✓ PASS");
      case "warn" -> sb.append("⚠ WARN");
      case "fail" -> sb.append("✗ FAIL");
      default -> sb.append("—");
    }
    return sb.toString();
  }

  // ============ 估值分析 ============

  private ValuationAnalysis buildValuation(
      TradeStockBasic basic, AStockDataQuoteService.QuoteSnapshot quote, FinancialAnalysis fin) {
    Double currentPrice =
        quote != null && quote.latestPrice() != null ? quote.latestPrice().doubleValue() : null;
    Double totalSharesYi =
        basic.getTotalShares() != null
            ? round2(basic.getTotalShares().doubleValue() / 1_0000_0000)
            : null;
    Double latestNm = fin != null ? fin.getLatestNetMargin() : null;

    // 市值（优先用 quote 里的 totalMarketCapYi）
    Double marketCap = null;
    if (quote != null && quote.totalMarketCapYi() != null) {
      marketCap = round2(quote.totalMarketCapYi().doubleValue());
    } else if (currentPrice != null && totalSharesYi != null) {
      marketCap = round2(currentPrice * totalSharesYi);
    }

    // 统一 10 倍 PS 法（适用于净利润率 ≥ 25% 的高科技公司）
    final double PS = 10.0;
    String method = "10 倍 PS 法";
    String methodReason;
    if (latestNm == null) {
      methodReason = "缺少净利率数据，按 25% 高科技公司默认值给 10 倍 PS（仅供参考）";
    } else if (latestNm >= 25) {
      methodReason = String.format(Locale.ROOT, "净利率 %.2f%% ≥ 25%%，适用 10 倍 PS 估值法", latestNm);
    } else {
      methodReason =
          String.format(Locale.ROOT, "净利率 %.2f%%，低于 25%% 基准线，10 倍 PS 仅供参考，需结合其他方法综合判断", latestNm);
    }

    // 预测营收：Y0 = 最近 TTM（只累加正数季度），Y1 = Y0 × 增速（用最新营收同比或保守 20%），Y2 = Y1 × 增速
    Double revY0 = null, revY1 = null, revY2 = null;
    if (fin != null && fin.getQuarters() != null && !fin.getQuarters().isEmpty()) {
      int sz = fin.getQuarters().size();
      // 只用正数季度累加（亏损季单季负值会导致 TTM 失真）
      double posSum = 0;
      int posCnt = 0;
      for (int i = Math.max(0, sz - 4); i < sz; i++) {
        Double r = fin.getQuarters().get(i).getRevenueYi();
        if (r != null && r > 0) {
          posSum += r;
          posCnt++;
        }
      }
      if (posCnt == 4) {
        revY0 = round2(posSum);
      } else if (posCnt > 0) {
        // 数据不全的兜底：按已有正数季均值外推到 4 个季度
        revY0 = round2(posSum / posCnt * 4);
      } else {
        // 全部负或缺失：取最近一期正值（取绝对值）×4
        for (int i = sz - 1; i >= 0; i--) {
          Double r = fin.getQuarters().get(i).getRevenueYi();
          if (r != null && r > 0) {
            revY0 = round2(r * 4);
            break;
          }
        }
      }

      Double yoy = fin.getLatestRevenueYoy();
      // 增速：最新营收同比为正则采用，但夹在 15%-50% 之间；为负或缺失则按 20% 保守估
      double growth;
      if (yoy != null && yoy > 0) {
        growth = Math.max(0.15, Math.min(0.50, yoy / 100));
      } else {
        growth = 0.20;
      }
      if (revY0 != null && revY0 > 0) {
        revY1 = round2(revY0 * (1 + growth));
        revY2 = round2(revY1 * (1 + growth));
      }
    }

    Double fairCapY1 = (revY1 != null) ? round2(revY1 * PS) : null;
    Double fairCapY2 = (revY2 != null) ? round2(revY2 * PS) : null;

    String verdict;
    String commentary;
    if (marketCap == null) {
      verdict = "—";
      commentary = "缺少市值数据，无法判定估值水平";
    } else if (fairCapY1 == null) {
      verdict = "—";
      commentary = "缺少预测营收，无法判定";
    } else if (marketCap < fairCapY1) {
      double discount = (fairCapY1 - marketCap) / fairCapY1 * 100;
      verdict = "低估";
      commentary =
          String.format(
              Locale.ROOT,
              "当前市值 %.1f 亿 < Y1×10=%.1f 亿，低于合理估值约 %.0f%%，性价比突出",
              marketCap,
              fairCapY1,
              discount);
    } else if (fairCapY2 != null && marketCap > fairCapY2) {
      double premium = (marketCap - fairCapY2) / fairCapY2 * 100;
      verdict = "泡沫";
      commentary =
          String.format(
              Locale.ROOT,
              "当前市值 %.1f 亿 > Y2×10=%.1f 亿，需 %.0f%% 的营收增长才能支撑，已透支未来",
              marketCap,
              fairCapY2,
              premium);
    } else {
      verdict = "合理";
      double premium = (marketCap - fairCapY1) / fairCapY1 * 100;
      commentary =
          String.format(
              Locale.ROOT,
              "当前市值 %.1f 亿在 Y1×10=%.1f 亿至 Y2×10=%.1f 亿区间，透支约 %.0f%%",
              marketCap,
              fairCapY1,
              fairCapY2 != null ? fairCapY2 : 0,
              premium);
    }

    String buildTip = null;
    if (verdict.equals("低估") || verdict.equals("合理")) {
      buildTip = "可考虑以最近大阳线起涨点为参考，逢回踩分批建仓观察仓";
    } else if (verdict.equals("泡沫")) {
      buildTip = "估值已透支，建议等回踩至合理区间再考虑";
    }

    return ValuationAnalysis.builder()
        .method(method)
        .methodReason(methodReason)
        .currentMarketCapYi(marketCap)
        .currentPrice(currentPrice)
        .totalSharesYi(totalSharesYi)
        .latestNetMargin(latestNm)
        .psMultiple(PS)
        .forecastRevenueY0(revY0)
        .forecastRevenueY1(revY1)
        .forecastRevenueY2(revY2)
        .fairCapY1Yi(fairCapY1)
        .fairCapY2Yi(fairCapY2)
        .verdict(verdict)
        .commentary(commentary)
        .buildPositionTip(buildTip)
        .build();
  }

  // ============ 星级评级（LLM + fallback） ============

  private StarRating buildRating(
      TradeStockBasic basic, FinancialAnalysis fin, ValuationAnalysis val, TrendAnalysis trend) {
    String sys = STAR_SYSTEM_PROMPT;
    String user = buildRatingUserPrompt(basic, fin, val, trend);
    String lastErr = null;

    // 1) MiniMax 优先
    if (aiProperties.getMinimax().isEnabled()) {
      try {
        String raw = miniMaxClient.chatComplete(sys, user);
        StarRating parsed = parseRatingJson(raw);
        if (parsed != null && parsed.getScarcityStars() != null) {
          parsed.setAiGenerated(true);
          parsed.setRawAiResponse(raw);
          return parsed;
        }
        lastErr = "MiniMax 响应解析失败";
      } catch (Exception e) {
        lastErr = "MiniMax: " + e.getMessage();
        log.warn("MiniMax 评级失败，尝试 SenseNova 兜底: {}", e.getMessage());
      }
    }

    // 2) SenseNova 兜底
    if (aiProperties.getSensenova() != null && aiProperties.getSensenova().isEnabled()) {
      try {
        String raw = senseNovaClient.chatComplete(sys, user);
        StarRating parsed = parseRatingJson(raw);
        if (parsed != null && parsed.getScarcityStars() != null) {
          parsed.setAiGenerated(true);
          parsed.setRawAiResponse(raw);
          return parsed;
        }
        lastErr = "SenseNova 响应解析失败";
      } catch (Exception e) {
        lastErr = (lastErr == null ? "" : lastErr + "; ") + "SenseNova: " + e.getMessage();
        log.warn("SenseNova 评级也失败，使用本地 fallback: {}", e.getMessage());
      }
    }

    return fallbackRating(basic, fin, val, lastErr == null ? "AI 未启用" : lastErr);
  }

  private StarRating parseRatingJson(String raw) {
    try {
      // 提取 JSON 块（容忍 ```json ``` 包裹或前后文本）
      String json = extractJsonBlock(raw);
      if (json == null) return null;
      JsonNode root = MAPPER.readTree(json);

      StarRating.StarRatingBuilder b = StarRating.builder();

      JsonNode sc = root.path("scarcity");
      if (sc.isObject()) {
        b.scarcityStars(getDoubleOrNull(sc.path("stars")))
            .scarcityStarsText(
                sc.path("starsText").asText(starsToText(getDoubleOrNull(sc.path("stars")))))
            .scarcitySummary(sc.path("summary").asText(""))
            .scarcityDimensions(parseDimensions(sc.path("dimensions")));
      }

      JsonNode gr = root.path("growth");
      if (gr.isObject()) {
        b.growthStars(getDoubleOrNull(gr.path("stars")))
            .growthStarsText(
                gr.path("starsText").asText(starsToText(getDoubleOrNull(gr.path("stars")))))
            .growthSummary(gr.path("summary").asText(""))
            .growthDimensions(parseDimensions(gr.path("dimensions")));

        List<String> weaknesses = new ArrayList<>();
        gr.path("weaknesses").forEach(n -> weaknesses.add(n.asText()));
        b.growthWeaknesses(weaknesses);
      }
      return b.build();
    } catch (Exception e) {
      log.warn("解析 AI 星级 JSON 失败: {}", e.getMessage());
      return null;
    }
  }

  private List<StarRating.DimensionRating> parseDimensions(JsonNode arr) {
    List<StarRating.DimensionRating> out = new ArrayList<>();
    if (arr.isArray()) {
      arr.forEach(
          n -> {
            out.add(
                StarRating.DimensionRating.builder()
                    .name(n.path("name").asText(""))
                    .stars(getDoubleOrNull(n.path("stars")))
                    .reason(n.path("reason").asText(""))
                    .build());
          });
    }
    return out;
  }

  private Double getDoubleOrNull(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) return null;
    if (n.isNumber()) return n.asDouble();
    try {
      return Double.parseDouble(n.asText());
    } catch (Exception e) {
      return null;
    }
  }

  private String extractJsonBlock(String text) {
    if (text == null) return null;
    String t = text.trim();
    if (t.startsWith("{")) {
      // 找最外层闭合
      int depth = 0;
      for (int i = 0; i < t.length(); i++) {
        char c = t.charAt(i);
        if (c == '{') depth++;
        else if (c == '}') {
          depth--;
          if (depth == 0) return t.substring(0, i + 1);
        }
      }
    }
    int first = t.indexOf("{");
    int last = t.lastIndexOf("}");
    if (first >= 0 && last > first) return t.substring(first, last + 1);
    return null;
  }

  private String starsToText(Double stars) {
    if (stars == null) return "—";
    StringBuilder sb = new StringBuilder();
    int full = (int) Math.floor(stars);
    boolean half = (stars - full) >= 0.5;
    for (int i = 0; i < 5; i++) {
      if (i < full) sb.append("★");
      else if (i == full && half) sb.append("☆");
      else sb.append("☆");
    }
    return sb.toString();
  }

  private StarRating fallbackRating(
      TradeStockBasic basic, FinancialAnalysis fin, ValuationAnalysis val, String reason) {
    // 本地启发式打分（AI 失败时使用）
    // 稀缺性：估值低估 + 业绩拐点 + 高毛利 → 4-5 星
    double scarcity = 0;
    if (val != null && "低估".equals(val.getVerdict())) scarcity += 2;
    else if (val != null && "合理".equals(val.getVerdict())) scarcity += 1;
    if (fin != null && fin.isTurnaroundDetected()) scarcity += 1;
    if (fin != null && fin.getLatestGrossMargin() != null && fin.getLatestGrossMargin() >= 40)
      scarcity += 1;
    if (fin != null && "pass".equals(fin.getSopVerdict())) scarcity += 1;
    scarcity = Math.min(5, scarcity);

    // 成长动力：营收同比 > 20% → +2，> 10% → +1；扣非 > 营收 → +1；ROE > 15 → +1
    double growth = 0;
    if (fin != null && fin.getLatestRevenueYoy() != null) {
      double yoy = fin.getLatestRevenueYoy();
      if (yoy >= 30) growth += 2;
      else if (yoy >= 10) growth += 1;
    }
    if (fin != null
        && fin.getLatestProfitYoy() != null
        && fin.getLatestRevenueYoy() != null
        && fin.getLatestProfitYoy() > fin.getLatestRevenueYoy()) {
      growth += 1;
    }
    if (fin != null && fin.getQuarters() != null && !fin.getQuarters().isEmpty()) {
      Double roe = fin.getQuarters().get(fin.getQuarters().size() - 1).getRoe();
      if (roe != null && roe >= 15) growth += 1;
    }
    if (fin != null && fin.isTurnaroundDetected()) growth += 1;
    growth = Math.min(5, growth);

    return StarRating.builder()
        .scarcityStars(scarcity)
        .scarcityStarsText(starsToText(scarcity))
        .scarcitySummary("(本地启发式评分 - AI 不可用: " + reason + ")")
        .scarcityDimensions(
            List.of(
                StarRating.DimensionRating.builder()
                    .name("估值水平")
                    .stars(val != null && "低估".equals(val.getVerdict()) ? 5.0 : 3.0)
                    .reason("基于 PS 估值结果自动评分")
                    .build(),
                StarRating.DimensionRating.builder()
                    .name("财务质量")
                    .stars(
                        fin != null && "pass".equals(fin.getSopVerdict())
                            ? 5.0
                            : "warn".equals(fin != null ? fin.getSopVerdict() : "") ? 3.0 : 2.0)
                    .reason("基于 SOP 体检结果自动评分")
                    .build(),
                StarRating.DimensionRating.builder()
                    .name("业绩拐点")
                    .stars(fin != null && fin.isTurnaroundDetected() ? 5.0 : 3.0)
                    .reason("营收是否由负转正")
                    .build()))
        .growthStars(growth)
        .growthStarsText(starsToText(growth))
        .growthSummary("(本地启发式评分)")
        .growthDimensions(
            List.of(
                StarRating.DimensionRating.builder()
                    .name("营收增速")
                    .stars(
                        fin != null
                                && fin.getLatestRevenueYoy() != null
                                && fin.getLatestRevenueYoy() >= 30
                            ? 5.0
                            : 3.0)
                    .reason("基于最近一期营收同比")
                    .build(),
                StarRating.DimensionRating.builder()
                    .name("盈利质量")
                    .stars(
                        fin != null
                                && fin.getLatestProfitYoy() != null
                                && fin.getLatestRevenueYoy() != null
                                && fin.getLatestProfitYoy() > fin.getLatestRevenueYoy()
                            ? 5.0
                            : 3.0)
                    .reason("扣非 vs 营收增速差")
                    .build()))
        .aiGenerated(false)
        .rawAiResponse(reason)
        .build();
  }

  private String buildRatingUserPrompt(
      TradeStockBasic basic, FinancialAnalysis fin, ValuationAnalysis val, TrendAnalysis trend) {
    StringBuilder sb = new StringBuilder();
    sb.append("# 公司基本信息\n");
    sb.append("- 股票代码：").append(basic.getStockCode()).append("\n");
    sb.append("- 公司简称：").append(basic.getStockName()).append("\n");
    sb.append("- 行业：")
        .append(basic.getSectorNames() == null ? "—" : basic.getSectorNames())
        .append("\n\n");

    sb.append("# 估值快照\n");
    if (val != null) {
      sb.append("- 估值方法：").append(val.getMethod()).append("\n");
      sb.append("- 当前市值：")
          .append(val.getCurrentMarketCapYi() == null ? "—" : val.getCurrentMarketCapYi() + " 亿")
          .append("\n");
      sb.append("- Y1×10 合理市值：")
          .append(val.getFairCapY1Yi() == null ? "—" : val.getFairCapY1Yi() + " 亿")
          .append("\n");
      sb.append("- Y2×10 合理市值：")
          .append(val.getFairCapY2Yi() == null ? "—" : val.getFairCapY2Yi() + " 亿")
          .append("\n");
      sb.append("- 估值结论：").append(val.getVerdict()).append("\n");
      sb.append("- PS 倍数依据：").append(val.getMethodReason()).append("\n\n");
    }

    sb.append("# 财务快照\n");
    if (fin != null && fin.getQuarters() != null && !fin.getQuarters().isEmpty()) {
      FinancialAnalysis.QuarterSnapshot latest =
          fin.getQuarters().get(fin.getQuarters().size() - 1);
      sb.append("- 最新季度：").append(latest.getQuarter()).append("\n");
      sb.append("- 营收同比：")
          .append(latest.getRevenueYoy() == null ? "—" : latest.getRevenueYoy() + "%")
          .append("\n");
      sb.append("- 毛利率：")
          .append(latest.getGrossMargin() == null ? "—" : latest.getGrossMargin() + "%")
          .append("\n");
      sb.append("- 净利率：")
          .append(latest.getNetMargin() == null ? "—" : latest.getNetMargin() + "%")
          .append("\n");
      sb.append("- ROE：")
          .append(latest.getRoe() == null ? "—" : latest.getRoe() + "%")
          .append("\n");
      sb.append("- 业绩复苏：").append(fin.isTurnaroundDetected() ? "是" : "否").append("\n");
      sb.append("- SOP 体检：")
          .append(fin.getSopVerdict())
          .append(" / ")
          .append(fin.getSopSummary())
          .append("\n\n");
    }

    sb.append("# 走势快照\n");
    if (trend != null) {
      sb.append("- 突破平台：").append(trend.isBreakoutDetected() ? "是" : "否").append("\n");
      sb.append("- 本月至今：")
          .append(
              trend.getMonthToDateReturnPct() == null ? "—" : trend.getMonthToDateReturnPct() + "%")
          .append("\n");
      sb.append("- 最近大阳线：")
          .append(
              trend.getRecentBigYang() == null || trend.getRecentBigYang().isEmpty()
                  ? "无"
                  : trend.getRecentBigYang().size() + " 根")
          .append("\n\n");
    }

    sb.append("# 任务\n");
    sb.append("请基于以上数据，按 A 股实战选股框架对该公司做稀缺性和成长动力综合评级，输出严格 JSON。\n\n");

    sb.append("## 输出格式（严格按此 JSON 结构，不要任何额外文字）\n");
    sb.append("```\n");
    sb.append("{\n");
    sb.append("  \"scarcity\": {\n");
    sb.append("    \"stars\": 4.5,\n");
    sb.append("    \"starsText\": \"★★★★☆\",\n");
    sb.append("    \"summary\": \"一段话总结稀缺性（80 字内）\",\n");
    sb.append("    \"dimensions\": [\n");
    sb.append("      {\"name\": \"技术稀缺\", \"stars\": 5.0, \"reason\": \"一句话理由\"},\n");
    sb.append("      {\"name\": \"客户资质\", \"stars\": 5.0, \"reason\": \"一句话理由\"},\n");
    sb.append("      {\"name\": \"商业模式\", \"stars\": 4.5, \"reason\": \"一句话理由\"}\n");
    sb.append("    ]\n");
    sb.append("  },\n");
    sb.append("  \"growth\": {\n");
    sb.append("    \"stars\": 4.0,\n");
    sb.append("    \"starsText\": \"★★★★☆\",\n");
    sb.append("    \"summary\": \"一段话总结成长动力（80 字内）\",\n");
    sb.append("    \"dimensions\": [\n");
    sb.append("      {\"name\": \"行业景气\", \"stars\": 5.0, \"reason\": \"一句话\"},\n");
    sb.append("      {\"name\": \"产能落地\", \"stars\": 5.0, \"reason\": \"一句话\"},\n");
    sb.append("      {\"name\": \"基本盘\", \"stars\": 4.0, \"reason\": \"一句话\"},\n");
    sb.append("      {\"name\": \"第三曲线\", \"stars\": 3.0, \"reason\": \"一句话\"}\n");
    sb.append("    ],\n");
    sb.append("    \"weaknesses\": [\n");
    sb.append("      \"降星原因 1\",\n");
    sb.append("      \"降星原因 2\"\n");
    sb.append("    ]\n");
    sb.append("  }\n");
    sb.append("}\n");
    sb.append("```\n\n");

    sb.append("## 评分准则\n");
    sb.append("- 稀缺性：技术壁垒 + 客户资源 + 商业模式 + A 股独特性，0-5 星\n");
    sb.append("- 成长动力：行业景气 + 产能落地 + 基本盘 + 第三曲线 + 短板扣分，0-5 星\n");
    sb.append("- 维度打分请保留 1 位小数\n");
    sb.append("- 文字简洁，每条理由 ≤ 30 字\n");
    return sb.toString();
  }

  private static final String STAR_SYSTEM_PROMPT =
      """
            你是 A 股实战选股分析师，熟悉龙江投资体系。你的任务是基于提供的财务 + 估值 + 走势数据，给出稀缺性和成长动力的星级评级。
            必须严格按照 JSON 输出，不要包含任何 JSON 之外的文字、解释、Markdown 包裹。
            维度评分保留 1 位小数，文字简洁客观，避免主观吹捧。
            """;

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

  private String basicOptName(String code) {
    return stockBasicRepository
        .findByStockCode(code)
        .map(TradeStockBasic::getStockName)
        .orElse(code);
  }

  private String formatQuarter(LocalDate d) {
    int yy = d.getYear() % 100;
    int q =
        switch (d.getMonthValue()) {
          case 3 -> 1;
          case 6 -> 2;
          case 9 -> 3;
          case 12 -> 4;
          default -> (d.getMonthValue() - 1) / 3 + 1;
        };
    return String.format("%02dQ%d", yy, q);
  }

  private double nullSafe(BigDecimal v) {
    return v == null ? 0 : v.doubleValue();
  }

  private double nullSafe(Double v) {
    return v == null ? 0 : v;
  }

  private Double round2(Double v) {
    if (v == null) return null;
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  private Double round2(BigDecimal v) {
    if (v == null) return null;
    return v.setScale(2, RoundingMode.HALF_UP).doubleValue();
  }
}
