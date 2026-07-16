package com.quant.service.practical;

import static com.quant.service.practical.PracticalSelectSupport.nullSafe;
import static com.quant.service.practical.PracticalSelectSupport.round2;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.quant.dto.practicalselect.TrendAnalysis;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;

import lombok.RequiredArgsConstructor;

/** 实战选股 · 走势分析（日 K 聚合月 K + 突破判定 + 大阳线 + 涨跌幅）。 */
@Component
@RequiredArgsConstructor
public class PracticalTrendAnalyzer {

  private static final int MONTHLY_LOOKBACK = 24; // 月线分析最长往前看 24 个月
  private static final double BIG_YANG_THRESHOLD = 9.5; // 大阳线阈值（涨幅 ≥ 9.5%）
  private static final double BREAKOUT_LOOKBACK_MONTHS = 6;
  private static final double BREAKOUT_THRESHOLD_PCT = 3.0;

  private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

  private final TradeStockDailyRepository dailyRepository;
  private final TradeStockBasicRepository stockBasicRepository;

  public TrendAnalysis buildTrend(String code) {
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

  private String basicOptName(String code) {
    return stockBasicRepository
        .findByStockCode(code)
        .map(TradeStockBasic::getStockName)
        .orElse(code);
  }
}
