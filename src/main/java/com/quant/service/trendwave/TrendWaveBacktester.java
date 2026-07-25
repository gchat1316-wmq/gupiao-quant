package com.quant.service.trendwave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.quant.config.TrendWaveProperties;
import com.quant.entity.MoneyPosition;
import com.quant.entity.MoneySetup;
import com.quant.entity.MoneyStockPool;
import com.quant.entity.MoneyWatch;
import com.quant.entity.TradeStockDaily;
import com.quant.service.technical.LimitUpDetector;
import com.quant.service.technical.LimitUpDetector.LimitUpStreak;
import com.quant.service.technical.MovingAverageCalculator;
import com.quant.service.technical.MovingAverageCalculator.MovingAverages;

import lombok.Builder;
import lombok.Data;

/**
 * 纯函数日线回测器（不依赖 DB/行情）。按交易日推进状态机，输出系统化收益指标。
 */
public class TrendWaveBacktester {

  private final TrendWaveProperties props;
  private final TrendWaveRuleEngine ruleEngine;
  private final LimitUpDetector limitUpDetector;

  public TrendWaveBacktester(TrendWaveProperties props) {
    this.props = props;
    this.ruleEngine = new TrendWaveRuleEngine(props);
    this.limitUpDetector = new LimitUpDetector();
  }

  @Data
  @Builder
  public static class TradeRecord {
    private String stockCode;
    private String buyType;
    private LocalDate entryDate;
    private LocalDate exitDate;
    private BigDecimal entryPrice;
    private BigDecimal exitPrice;
    private BigDecimal pnlPct;
    private String exitReason;
  }

  @Data
  @Builder
  public static class BacktestResult {
    private String stockCode;
    private int bars;
    private int trades;
    private int wins;
    private int losses;
    private BigDecimal winRate;
    private BigDecimal avgWinPct;
    private BigDecimal avgLossPct;
    private BigDecimal profitFactor;
    private BigDecimal expectancyPct;
    private BigDecimal totalReturnPct; // 复利连乘
    private BigDecimal maxDrawdownPct;
    private List<TradeRecord> tradeRecords;
    private String summary;
  }

  public BacktestResult run(String stockCode, String stockName, List<TradeStockDaily> allDaily) {
    List<TradeStockDaily> asc = MovingAverageCalculator.sortedAsc(allDaily);
    if (asc.size() < 80) {
      return empty(stockCode, "K线不足80根");
    }

    MoneyStockPool pool = new MoneyStockPool();
    pool.setPaperMode(1);
    pool.setStockCode(stockCode);

    MoneyWatch watch = new MoneyWatch();
    watch.setId(1L);
    watch.setStockCode(stockCode);
    watch.setStockName(stockName);
    watch.setStatus("SCREENING");
    watch.setScreenPassed(0);
    watch.setActiveFlag(1);

    MoneySetup setup = null;
    MoneyPosition pos = null;
    List<TradeRecord> trades = new ArrayList<>();
    BigDecimal equity = BigDecimal.ONE;
    BigDecimal peakEquity = BigDecimal.ONE;
    BigDecimal maxDd = BigDecimal.ZERO;

    // 从第 70 根开始，保证 MA60 可用
    for (int i = 70; i < asc.size(); i++) {
      List<TradeStockDaily> window = asc.subList(0, i + 1);
      TradeStockDaily bar = asc.get(i);
      MovingAverages mas = MovingAverageCalculator.fromDaily(window);
      BigDecimal highNear = MovingAverageCalculator.highNearRatio(window, Math.min(756, window.size()));
      Map<String, Object> screen =
          ruleEngine.screenDetail(mas, highNear, true, true, BigDecimal.valueOf(40));
      boolean passed = Boolean.TRUE.equals(screen.get("passed"));
      watch.setScreenPassed(passed ? 1 : 0);
      watch.setScreenDetail(null);

      // 无持仓时维护形态（即使当日筛选暂未通过，已识别的涨停平台仍保持观察）
      if (pos == null) {
        LimitUpStreak streak =
            limitUpDetector.detectLatestStreak(
                stockCode,
                stockName,
                window,
                props.getPullback().getMinLimitUp(),
                props.getPullback().getMaxLimitUp(),
                props.getPullback().getLookbackDays());
        if (streak != null) {
          if (setup == null || !"PULLBACK".equals(setup.getSetupType()) || !"TRIGGERED".equals(setup.getStatus())) {
            if (setup == null) setup = new MoneySetup();
            setup.setId(1L);
            setup.setWatchId(1L);
            setup.setSetupType("PULLBACK");
            if (!"TRIGGERED".equals(setup.getStatus())) {
              setup.setStatus("ACTIVE");
            }
            setup.setLimitUpCount(streak.streakDays());
            setup.setPlatformOpen(streak.firstOpen());
            BigDecimal low =
                streak.firstLow() != null ? streak.firstLow() : streak.firstOpen();
            // 保证 platformLow <= platformOpen
            if (low != null && streak.firstOpen() != null && low.compareTo(streak.firstOpen()) > 0) {
              setup.setPlatformLow(streak.firstOpen());
              setup.setPlatformOpen(low);
            } else {
              setup.setPlatformLow(low);
            }
            setup.setLimitUpVolume(streak.firstVolume());
          }
          if (!"HOLDING".equals(watch.getStatus()) && !"PARTIAL_EXIT".equals(watch.getStatus())
              && !"BUY_SIGNAL".equals(watch.getStatus())) {
            watch.setStatus("WATCH_PULLBACK");
          }
        } else if (passed
            && (setup == null || !"ACTIVE".equals(setup.getStatus()))) {
          int days = props.getBreakout().getMaxPlatformDays();
          if (window.size() > days + 5 && mas.bullishAlignment() && mas.aboveMa20()) {
            List<TradeStockDaily> plat = window.subList(window.size() - days, window.size());
            if (ruleEngine.isPlatformTighten(plat, props.getBreakout().getRangeTightenPct())) {
              setup = new MoneySetup();
              setup.setId(2L);
              setup.setWatchId(1L);
              setup.setSetupType("BREAKOUT");
              setup.setStatus("ACTIVE");
              setup.setPlatformHigh(ruleEngine.platformHigh(plat));
              setup.setPlatformDays(days);
              watch.setStatus("WATCH_BREAKOUT");
            }
          }
        } else if (setup == null) {
          watch.setStatus("SCREENING");
        }
      }

      List<MoneySetup> setups =
          setup == null || !"ACTIVE".equals(setup.getStatus()) && !"TRIGGERED".equals(setup.getStatus())
              ? List.of()
              : List.of(setup);

      TrendWaveContext ctx =
          TrendWaveContext.builder()
              .pool(pool)
              .watch(watch)
              .setups(setups)
              .position(pos)
              .dailyAsc(window)
              .mas(mas)
              .latestPrice(bar.getClosePrice())
              .todayOpen(bar.getOpenPrice())
              .todayHigh(bar.getHighPrice())
              .todayLow(bar.getLowPrice())
              .todayVolume(bar.getVolume())
              .eodScan(true)
              .indexAboveMa20(true)
              .marketRegime("BULL")
              .now(bar.getTradeDate().atTime(15, 10))
              .build();

      List<TrendWaveSignal> signals = ruleEngine.evaluate(ctx);

      // 持仓动态
      if (pos != null) {
        if (pos.getPeakPrice() == null
            || bar.getHighPrice() != null && bar.getHighPrice().compareTo(pos.getPeakPrice()) > 0) {
          pos.setPeakPrice(
              bar.getHighPrice() != null ? bar.getHighPrice() : bar.getClosePrice());
        }
        BigDecimal profitPct =
            bar.getClosePrice()
                .subtract(pos.getEntryPrice())
                .divide(pos.getEntryPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        pos.setProfitTier(ruleEngine.resolveTier(profitPct, props.getTakeProfit()));
        pos.setTrailingStop(
            ruleEngine.trailingStopPrice(pos.getPeakPrice(), pos.getProfitTier()));
        if (!mas.aboveMa20()) {
          pos.setBelowMa20Days((pos.getBelowMa20Days() == null ? 0 : pos.getBelowMa20Days()) + 1);
        } else {
          pos.setBelowMa20Days(0);
        }
      }

      for (TrendWaveSignal sig : signals) {
        if (!sig.isMutateState()) continue;

        if ("BUY_SIGNAL".equals(sig.getNextWatchStatus()) && pos == null) {
          // 次日开盘近似：用当日收盘作入场（回测保守）
          pos = new MoneyPosition();
          pos.setId(1L);
          pos.setWatchId(1L);
          pos.setStockCode(stockCode);
          pos.setBuyType(
              sig.getEventType() != null && sig.getEventType().contains("BREAKOUT")
                  ? "BREAKOUT"
                  : "PULLBACK");
          pos.setEntryPrice(bar.getClosePrice());
          pos.setEntryDate(bar.getTradeDate().atStartOfDay());
          pos.setEntryShares(100);
          pos.setPositionPct(BigDecimal.valueOf(100));
          pos.setPeakPrice(bar.getClosePrice());
          pos.setProfitTier("T0");
          pos.setStopPrimary(ruleEngine.calcStopPrimary(pos.getBuyType(), setup, pos.getEntryPrice()));
          pos.setStopSecondary(ruleEngine.calcStopSecondary(pos.getBuyType(), pos.getEntryPrice()));
          pos.setStatus("HOLDING");
          watch.setStatus("HOLDING");
          if (setup != null) {
            setup.setStatus("TRIGGERED");
            setup.setTriggerPrice(bar.getClosePrice());
            setup.setTriggerAt(LocalDateTime.of(bar.getTradeDate(), java.time.LocalTime.NOON));
          }
        } else if (pos != null
            && ("CLOSED".equals(sig.getNextWatchStatus())
                || (sig.getNextPositionPct() != null
                    && sig.getNextPositionPct().compareTo(BigDecimal.ZERO) == 0))) {
          BigDecimal exit = bar.getClosePrice();
          BigDecimal pnl =
              exit.subtract(pos.getEntryPrice())
                  .divide(pos.getEntryPrice(), 6, RoundingMode.HALF_UP)
                  .multiply(BigDecimal.valueOf(100))
                  .setScale(2, RoundingMode.HALF_UP);
          trades.add(
              TradeRecord.builder()
                  .stockCode(stockCode)
                  .buyType(pos.getBuyType())
                  .entryDate(pos.getEntryDate().toLocalDate())
                  .exitDate(bar.getTradeDate())
                  .entryPrice(pos.getEntryPrice())
                  .exitPrice(exit)
                  .pnlPct(pnl)
                  .exitReason(sig.getEventType())
                  .build());
          equity =
              equity.multiply(
                  BigDecimal.ONE.add(pnl.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
          if (equity.compareTo(peakEquity) > 0) peakEquity = equity;
          BigDecimal dd =
              peakEquity
                  .subtract(equity)
                  .divide(peakEquity, 6, RoundingMode.HALF_UP)
                  .multiply(BigDecimal.valueOf(100));
          if (dd.compareTo(maxDd) > 0) maxDd = dd;

          pos = null;
          setup = null;
          watch.setStatus("SCREENING");
          watch.setBuySignalType(null);
        } else if (pos != null && "PARTIAL_EXIT".equals(sig.getNextWatchStatus())) {
          // 半仓：简化为按 sellPct 锁定部分收益，剩余仓位继续
          BigDecimal sellPct =
              pos.getPositionPct()
                  .subtract(
                      sig.getNextPositionPct() == null
                          ? BigDecimal.valueOf(50)
                          : sig.getNextPositionPct());
          if (sellPct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pnl =
                bar.getClosePrice()
                    .subtract(pos.getEntryPrice())
                    .divide(pos.getEntryPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            // 半仓对权益的贡献按比例
            BigDecimal weight = sellPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            equity =
                equity.multiply(
                    BigDecimal.ONE.add(
                        pnl.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                            .multiply(weight)));
            pos.setPositionPct(
                sig.getNextPositionPct() == null
                    ? BigDecimal.valueOf(50)
                    : sig.getNextPositionPct());
            pos.setStatus("PARTIAL_EXIT");
            pos.setCostStop(pos.getEntryPrice());
            watch.setStatus("PARTIAL_EXIT");
          }
        }
      }
    }

    // 未平仓按最后收盘强平记账（标记 OPEN_MARK）
    if (pos != null) {
      TradeStockDaily last = asc.get(asc.size() - 1);
      BigDecimal pnl =
          last.getClosePrice()
              .subtract(pos.getEntryPrice())
              .divide(pos.getEntryPrice(), 6, RoundingMode.HALF_UP)
              .multiply(BigDecimal.valueOf(100))
              .setScale(2, RoundingMode.HALF_UP);
      trades.add(
          TradeRecord.builder()
              .stockCode(stockCode)
              .buyType(pos.getBuyType())
              .entryDate(pos.getEntryDate().toLocalDate())
              .exitDate(last.getTradeDate())
              .entryPrice(pos.getEntryPrice())
              .exitPrice(last.getClosePrice())
              .pnlPct(pnl)
              .exitReason("OPEN_MARK")
              .build());
      equity =
          equity.multiply(
              BigDecimal.ONE.add(pnl.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
    }

    return summarize(stockCode, asc.size(), trades, equity, maxDd);
  }

  public BacktestResult runBasket(List<BacktestResult> perStock) {
    List<TradeRecord> all = new ArrayList<>();
    for (BacktestResult r : perStock) {
      if (r.getTradeRecords() != null) all.addAll(r.getTradeRecords());
    }
    all.sort(Comparator.comparing(TradeRecord::getExitDate, Comparator.nullsLast(Comparator.naturalOrder())));
    BigDecimal equity = BigDecimal.ONE;
    BigDecimal peak = BigDecimal.ONE;
    BigDecimal maxDd = BigDecimal.ZERO;
    for (TradeRecord t : all) {
      if (t.getPnlPct() == null) continue;
      equity =
          equity.multiply(
              BigDecimal.ONE.add(
                  t.getPnlPct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
      if (equity.compareTo(peak) > 0) peak = equity;
      BigDecimal dd =
          peak.subtract(equity)
              .divide(peak, 6, RoundingMode.HALF_UP)
              .multiply(BigDecimal.valueOf(100));
      if (dd.compareTo(maxDd) > 0) maxDd = dd;
    }
    BacktestResult agg = summarize("BASKET", 0, all, equity, maxDd);
    agg.setSummary(
        String.format(
            "组合回测：%d只标的，%d笔交易，胜率%s%%，盈亏比%s，期望%s%%，复利总收益%s%%，最大回撤%s%%",
            perStock.size(),
            agg.getTrades(),
            agg.getWinRate(),
            agg.getProfitFactor(),
            agg.getExpectancyPct(),
            agg.getTotalReturnPct(),
            agg.getMaxDrawdownPct()));
    return agg;
  }

  private BacktestResult summarize(
      String code, int bars, List<TradeRecord> trades, BigDecimal equity, BigDecimal maxDd) {
    long wins = trades.stream().filter(t -> t.getPnlPct() != null && t.getPnlPct().signum() > 0).count();
    long losses = trades.stream().filter(t -> t.getPnlPct() != null && t.getPnlPct().signum() < 0).count();
    BigDecimal winSum =
        trades.stream()
            .filter(t -> t.getPnlPct() != null && t.getPnlPct().signum() > 0)
            .map(TradeRecord::getPnlPct)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal lossSum =
        trades.stream()
            .filter(t -> t.getPnlPct() != null && t.getPnlPct().signum() < 0)
            .map(t -> t.getPnlPct().abs())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    int n = trades.size();
    BigDecimal winRate =
        n == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    BigDecimal avgWin =
        wins == 0 ? BigDecimal.ZERO : winSum.divide(BigDecimal.valueOf(wins), 2, RoundingMode.HALF_UP);
    BigDecimal avgLoss =
        losses == 0
            ? BigDecimal.ZERO
            : lossSum.divide(BigDecimal.valueOf(losses), 2, RoundingMode.HALF_UP);
    BigDecimal pf =
        avgLoss.signum() == 0
            ? (wins > 0 ? BigDecimal.valueOf(999) : BigDecimal.ZERO)
            : avgWin.divide(avgLoss, 2, RoundingMode.HALF_UP);
    BigDecimal expectancy =
        n == 0
            ? BigDecimal.ZERO
            : winRate
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                .multiply(avgWin)
                .subtract(
                    BigDecimal.ONE
                        .subtract(winRate.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
                        .multiply(avgLoss))
                .setScale(2, RoundingMode.HALF_UP);
    BigDecimal totalReturn =
        equity
            .subtract(BigDecimal.ONE)
            .multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP);

    String summary =
        String.format(
            "%s：交易%d笔 胜率%s%% 盈亏比%s 期望%s%% 复利收益%s%% 最大回撤%s%%",
            code, n, winRate, pf, expectancy, totalReturn, maxDd.setScale(2, RoundingMode.HALF_UP));

    return BacktestResult.builder()
        .stockCode(code)
        .bars(bars)
        .trades(n)
        .wins((int) wins)
        .losses((int) losses)
        .winRate(winRate)
        .avgWinPct(avgWin)
        .avgLossPct(avgLoss)
        .profitFactor(pf)
        .expectancyPct(expectancy)
        .totalReturnPct(totalReturn)
        .maxDrawdownPct(maxDd.setScale(2, RoundingMode.HALF_UP))
        .tradeRecords(trades)
        .summary(summary)
        .build();
  }

  private BacktestResult empty(String code, String reason) {
    return BacktestResult.builder()
        .stockCode(code)
        .bars(0)
        .trades(0)
        .wins(0)
        .losses(0)
        .winRate(BigDecimal.ZERO)
        .avgWinPct(BigDecimal.ZERO)
        .avgLossPct(BigDecimal.ZERO)
        .profitFactor(BigDecimal.ZERO)
        .expectancyPct(BigDecimal.ZERO)
        .totalReturnPct(BigDecimal.ZERO)
        .maxDrawdownPct(BigDecimal.ZERO)
        .tradeRecords(List.of())
        .summary(code + ": " + reason)
        .build();
  }
}
