package com.quant.service.trendwave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.quant.config.TrendWaveProperties;
import com.quant.entity.MoneyPosition;
import com.quant.entity.MoneySetup;
import com.quant.entity.TradeStockDaily;
import com.quant.service.technical.MovingAverageCalculator;
import com.quant.service.technical.MovingAverageCalculator.MovingAverages;

import lombok.RequiredArgsConstructor;

/**
 * 纯规则引擎：过滤 → 止损 → 止盈 → 买点 → 加仓。不做持久化/推送。
 */
@Component
@RequiredArgsConstructor
public class TrendWaveRuleEngine {

  private final TrendWaveProperties props;

  public List<TrendWaveSignal> evaluate(TrendWaveContext ctx) {
    List<TrendWaveSignal> out = new ArrayList<>();
    if (ctx == null || ctx.getWatch() == null || ctx.getLatestPrice() == null) {
      return out;
    }
    String status = ctx.getWatch().getStatus();
    if ("CLOSED".equals(status) || "INVALID".equals(status)) {
      return out;
    }

    if (isHolding(status)) {
      evalFiltersOnHold(ctx, out);
      if (!out.isEmpty() && out.stream().anyMatch(s -> "INVALID".equals(s.getNextWatchStatus()))) {
        return out;
      }
      evalStopLoss(ctx, out);
      if (!out.isEmpty() && out.stream().anyMatch(s -> "CLOSED".equals(s.getNextWatchStatus()))) {
        return out;
      }
      evalTakeProfit(ctx, out);
      evalAddPosition(ctx, out);
      return out;
    }

    // 观察/买点阶段
    evalFiltersOnWatch(ctx, out);
    if (!out.isEmpty() && out.stream().anyMatch(s -> "INVALID".equals(s.getNextWatchStatus()))) {
      return out;
    }
    evalBuySignals(ctx, out);
    evalBuySignalExpiry(ctx, out);
    return out;
  }

  private void evalFiltersOnWatch(TrendWaveContext ctx, List<TrendWaveSignal> out) {
    MoneySetup pullback = activeSetup(ctx, "PULLBACK");
    if (pullback != null && isVolumeDumpOnPullback(ctx, pullback)) {
      out.add(
          signal(
              "FILTER_VOLUME_DUMP",
              "ACTION",
              title(ctx, "回踩放量下跌，过滤"),
              "回踩区间出现放量阴线，判定资金出逃，标记无效。",
              ctx.getLatestPrice(),
              Map.of("setupId", pullback.getId()),
              true,
              "INVALID",
              "EXPIRED",
              null,
              null,
              "FILTER_VOLUME_DUMP",
              false));
      out.get(out.size() - 1).setSetupId(pullback.getId());
      return;
    }
    // 板块/个股破 20 日线：观察阶段若连续破位则无效
    MovingAverages mas = ctx.getMas();
    if (ctx.isEodScan() && mas != null && !mas.aboveMa20() && isWatching(ctx.getWatch().getStatus())) {
      out.add(
          signal(
              "FILTER_BELOW_MA20",
              "WARN",
              title(ctx, "跌破20日线"),
              "观察标的收盘跌破20日线，趋势条件失效。",
              ctx.getLatestPrice(),
              Map.of("ma20", str(mas.ma20())),
              true,
              "INVALID",
              null,
              null,
              null,
              "FILTER_BELOW_MA20",
              false));
    }
  }

  private void evalFiltersOnHold(TrendWaveContext ctx, List<TrendWaveSignal> out) {
    // 持仓阶段过滤不直接 INVALID 仓位，交给止损/趋势终结
  }

  private void evalStopLoss(TrendWaveContext ctx, List<TrendWaveSignal> out) {
    MoneyPosition pos = ctx.getPosition();
    if (pos == null) return;
    BigDecimal latest = ctx.getLatestPrice();

    // 兜底比例止损
    BigDecimal secondary = pos.getStopSecondary();
    if (secondary != null && latest.compareTo(secondary) <= 0) {
      out.add(
          closeSignal(
              ctx,
              "STOP_SECONDARY",
              "兜底止损触发",
              "价格触及/跌破兜底止损位 " + secondary + "，强制清仓。",
              latest,
              Map.of("stopSecondary", secondary),
              true));
      return;
    }

    // 第一止损：逻辑失效位
    BigDecimal primary = pos.getStopPrimary();
    if (primary != null && latest.compareTo(primary) <= 0) {
      boolean confirm =
          ctx.isEodScan()
              || isIntradayVolumeBreak(ctx)
              || pos.getBelowMa20Days() != null && pos.getBelowMa20Days() >= 1;
      if (ctx.isEodScan() || confirm) {
        out.add(
            closeSignal(
                ctx,
                "STOP_PRIMARY",
                "第一止损触发",
                "价格有效跌破买入逻辑失效位 " + primary + "，立刻止损。",
                latest,
                Map.of("stopPrimary", primary, "eod", ctx.isEodScan()),
                true));
        return;
      }
    }

    // 成本止损（T1+）
    if (pos.getCostStop() != null && latest.compareTo(pos.getCostStop()) <= 0) {
      out.add(
          closeSignal(
              ctx,
              "STOP_COST",
              "保本止损触发",
              "盈利回撤至成本线，保本离场。",
              latest,
              Map.of("costStop", pos.getCostStop()),
              true));
      return;
    }

    // 趋势终极：破 20 日线
    MovingAverages mas = ctx.getMas();
    if (ctx.isEodScan() && mas != null && !mas.aboveMa20()) {
      int days = pos.getBelowMa20Days() == null ? 0 : pos.getBelowMa20Days();
      // 调用方会在扫描时 +1；此处用 days+1 判断
      int next = days + 1;
      if (next >= props.getStopLoss().getBelowMa20ConfirmDays()) {
        out.add(
            closeSignal(
                ctx,
                "STOP_MA20",
                "跌破20日线清仓",
                "收盘连续 "
                    + next
                    + " 日站不上20日线，趋势终结清仓。",
                latest,
                Map.of("belowMa20Days", next, "ma20", str(mas.ma20())),
                true));
        return;
      } else if (next == 1 && pos.getPositionPct() != null
          && pos.getPositionPct().compareTo(BigDecimal.valueOf(100)) >= 0) {
        // 首次破 20 日线减半
        out.add(
            TrendWaveSignal.builder()
                .eventType("STOP_MA20_HALF")
                .severity("ACTION")
                .title(title(ctx, "破20日线减仓50%"))
                .content("首次收盘跌破20日线，先减仓50%，次日确认后再清仓。")
                .triggerPrice(latest)
                .triggerData(Map.of("ma20", str(mas.ma20())))
                .mutateState(true)
                .nextWatchStatus("PARTIAL_EXIT")
                .nextPositionStatus("PARTIAL_EXIT")
                .nextPositionPct(BigDecimal.valueOf(50))
                .paperAutoExecute(isPaper(ctx))
                .build());
        return;
      }
    }

    // 10/20 死叉
    if (ctx.isEodScan() && mas != null && mas.deathCross10_20()) {
      out.add(
          closeSignal(
              ctx,
              "STOP_DEATH_CROSS",
              "10日线下穿20日线",
              "短期趋势反转（10/20死叉），清仓剩余仓位。",
              latest,
              Map.of("ma10", str(mas.ma10()), "ma20", str(mas.ma20())),
              true));
    }
  }

  private void evalTakeProfit(TrendWaveContext ctx, List<TrendWaveSignal> out) {
    MoneyPosition pos = ctx.getPosition();
    if (pos == null || pos.getPeakPrice() == null || pos.getEntryPrice() == null) return;
    BigDecimal latest = ctx.getLatestPrice();
    BigDecimal entry = effectiveEntry(pos);
    BigDecimal profitPct =
        latest.subtract(entry).divide(entry, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    BigDecimal peak = pos.getPeakPrice();
    if (latest.compareTo(peak) > 0) {
      // peak 由扫描服务更新；此处用当前价
      peak = latest;
    }
    BigDecimal drawdownPct =
        peak.subtract(latest).divide(peak, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

    TrendWaveProperties.TakeProfit tp = props.getTakeProfit();

    // 单日暴跌
    if (ctx.getTodayOpen() != null
        && ctx.getTodayOpen().compareTo(BigDecimal.ZERO) > 0
        && latest
                .subtract(ctx.getTodayOpen())
                .divide(ctx.getTodayOpen(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .abs()
                .compareTo(tp.getFlashCrashPct())
            >= 0
        && latest.compareTo(ctx.getTodayOpen()) < 0
        && profitPct.compareTo(tp.getTier3ProfitPct()) > 0) {
      boolean volOk =
          ctx.getMas() != null
              && ctx.getMas().volMa5() != null
              && ctx.getTodayVolume() != null
              && BigDecimal.valueOf(ctx.getTodayVolume())
                      .compareTo(
                          BigDecimal.valueOf(ctx.getMas().volMa5())
                              .multiply(tp.getFlashCrashVolRatio()))
                  >= 0;
      if (volOk || ctx.isEodScan()) {
        BigDecimal remain = pos.getPositionPct() == null ? BigDecimal.valueOf(100) : pos.getPositionPct();
        if (remain.compareTo(BigDecimal.valueOf(50)) > 0) {
          out.add(
              TrendWaveSignal.builder()
                  .eventType("TP_FLASH_CRASH")
                  .severity("ACTION")
                  .title(title(ctx, "高位放量急跌减半"))
                  .content(
                      "盈利>"
                          + tp.getTier3ProfitPct()
                          + "% 且单日跌幅≥"
                          + tp.getFlashCrashPct()
                          + "%，减仓一半。")
                  .triggerPrice(latest)
                  .triggerData(Map.of("profitPct", profitPct, "drawdownPct", drawdownPct))
                  .mutateState(true)
                  .nextWatchStatus("PARTIAL_EXIT")
                  .nextPositionStatus("PARTIAL_EXIT")
                  .nextPositionPct(remain.multiply(BigDecimal.valueOf(0.5)).setScale(2, RoundingMode.HALF_UP))
                  .paperAutoExecute(isPaper(ctx))
                  .build());
          return;
        }
      }
    }

    String tier = resolveTier(profitPct, tp);
    BigDecimal ddLimit = drawdownLimit(tier, tp);
    if (ddLimit == null) {
      return; // T0 不止盈
    }
    if (drawdownPct.compareTo(ddLimit) >= 0) {
      boolean alreadyPartial = "PARTIAL_EXIT".equals(pos.getStatus());
      if (!alreadyPartial) {
        out.add(
            TrendWaveSignal.builder()
                .eventType("TP_" + tier + "_50PCT")
                .severity("ACTION")
                .title(title(ctx, "移动止盈卖出50%"))
                .content(
                    "盈利 "
                        + profitPct.setScale(2, RoundingMode.HALF_UP)
                        + "%，最高价回撤 "
                        + drawdownPct.setScale(2, RoundingMode.HALF_UP)
                        + "% ≥ "
                        + ddLimit
                        + "%，先锁定半仓利润。")
                .triggerPrice(latest)
                .triggerData(
                    Map.of(
                        "tier",
                        tier,
                        "profitPct",
                        profitPct,
                        "drawdownPct",
                        drawdownPct,
                        "ddLimit",
                        ddLimit))
                .mutateState(true)
                .nextWatchStatus("PARTIAL_EXIT")
                .nextPositionStatus("PARTIAL_EXIT")
                .nextPositionPct(tp.getFirstSellPct())
                .paperAutoExecute(isPaper(ctx))
                .build());
      } else {
        out.add(
            closeSignal(
                ctx,
                "TP_" + tier + "_CLEAR",
                "二次止盈清仓",
                "半仓后回撤继续扩大/二次触发，清仓剩余仓位。",
                latest,
                Map.of("tier", tier, "drawdownPct", drawdownPct),
                true));
      }
    }
  }

  private void evalAddPosition(TrendWaveContext ctx, List<TrendWaveSignal> out) {
    MoneyPosition pos = ctx.getPosition();
    if (pos == null || integerTrue(pos.getAddPositionDone())) return;
    if (!"HOLDING".equals(pos.getStatus())) return;
    MovingAverages mas = ctx.getMas();
    if (mas == null || mas.ma5() == null) return;
    BigDecimal entry = effectiveEntry(pos);
    BigDecimal profitPct =
        ctx.getLatestPrice()
            .subtract(entry)
            .divide(entry, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    if (profitPct.compareTo(props.getAddPosition().getMinProfitPct()) < 0) return;
    // 回踩5日线：最新价接近 ma5（±1.5%）
    BigDecimal band =
        mas.ma5().multiply(BigDecimal.valueOf(0.015));
    if (ctx.getLatestPrice().subtract(mas.ma5()).abs().compareTo(band) <= 0
        && ctx.getLatestPrice().compareTo(mas.ma5()) >= 0) {
      out.add(
          TrendWaveSignal.builder()
              .eventType("ADD_POSITION_SIGNAL")
              .severity("ACTION")
              .title(title(ctx, "盈利回踩5日线可加仓"))
              .content(
                  "盈利已超 "
                      + props.getAddPosition().getMinProfitPct()
                      + "%，股价回踩5日线，可金字塔加仓一次（≤底仓50%）。")
              .triggerPrice(ctx.getLatestPrice())
              .triggerData(Map.of("ma5", mas.ma5(), "profitPct", profitPct))
              .mutateState(false)
              .paperAutoExecute(false)
              .build());
    }
  }

  private void evalBuySignals(TrendWaveContext ctx, List<TrendWaveSignal> out) {
    String status = ctx.getWatch().getStatus();
    if ("BUY_SIGNAL".equals(status)) {
      return;
    }
    if (!integerTrue(ctx.getWatch().getScreenPassed())
        && !"WATCH_PULLBACK".equals(status)
        && !"WATCH_BREAKOUT".equals(status)) {
      return;
    }

    MoneySetup pullback = activeSetup(ctx, "PULLBACK");
    if (pullback != null && ("WATCH_PULLBACK".equals(status) || "SCREENING".equals(status))) {
      TrendWaveSignal s = evalPullbackBuy(ctx, pullback);
      if (s != null) out.add(s);
    }
    MoneySetup breakout = activeSetup(ctx, "BREAKOUT");
    if (breakout != null && ("WATCH_BREAKOUT".equals(status) || "SCREENING".equals(status))) {
      TrendWaveSignal s = evalBreakoutBuy(ctx, breakout);
      if (s != null) out.add(s);
    }
  }

  private TrendWaveSignal evalPullbackBuy(TrendWaveContext ctx, MoneySetup setup) {
    if (setup.getPlatformLow() == null || setup.getPlatformOpen() == null) return null;
    BigDecimal price = ctx.getLatestPrice();
    BigDecimal low = setup.getPlatformLow();
    BigDecimal open = setup.getPlatformOpen();
    BigDecimal zoneLow = low.min(open);
    BigDecimal zoneHigh = low.max(open);

    boolean inZone = price.compareTo(zoneLow) >= 0 && price.compareTo(zoneHigh) <= 0;
    boolean touched =
        ctx.getTodayLow() != null
            && ctx.getTodayLow().compareTo(zoneHigh) <= 0
            && ctx.getTodayLow().compareTo(zoneLow.multiply(BigDecimal.valueOf(0.98))) >= 0;

    // 缩量确认（EOD 用近日量，盘中用今日量近似）
    boolean shrinkOk = true;
    if (setup.getLimitUpVolume() != null && setup.getLimitUpVolume() > 0) {
      Long vol =
          ctx.isEodScan() && ctx.getMas() != null
              ? ctx.getMas().latestVolume()
              : ctx.getTodayVolume();
      if (vol != null) {
        shrinkOk =
            BigDecimal.valueOf(vol)
                    .compareTo(
                        BigDecimal.valueOf(setup.getLimitUpVolume())
                            .multiply(props.getPullback().getShrinkVolumeRatio()))
                <= 0;
      }
    }

    MovingAverages mas = ctx.getMas();
    boolean reclaimMa5 =
        mas != null
            && mas.ma5() != null
            && price.compareTo(mas.ma5()) >= 0
            && (ctx.getTodayOpen() == null || price.compareTo(ctx.getTodayOpen()) > 0);

    boolean intradayRecover =
        touched
            && ctx.getTodayLow() != null
            && price.compareTo(ctx.getTodayLow().multiply(BigDecimal.valueOf(1.01))) > 0
            && reclaimMa5;

    if ((inZone || touched) && shrinkOk && (reclaimMa5 || intradayRecover)) {
      String type = intradayRecover && !ctx.isEodScan() ? "BUY_INTRADAY_RECOVER" : "BUY_PULLBACK";
      TrendWaveSignal sig =
          TrendWaveSignal.builder()
              .eventType(type)
              .severity("ACTION")
              .title(title(ctx, "回踩买点触发"))
              .content(
                  String.format(
                      "股价回踩涨停平台 [%.2f ~ %.2f]%s，站上5日线，建议介入。",
                      zoneLow, zoneHigh, shrinkOk ? "且缩量" : ""))
              .triggerPrice(price)
              .triggerData(
                  Map.of(
                      "zoneLow",
                      zoneLow,
                      "zoneHigh",
                      zoneHigh,
                      "setupId",
                      setup.getId(),
                      "ma5",
                      str(mas == null ? null : mas.ma5())))
              .setupId(setup.getId())
              .mutateState(true)
              .nextWatchStatus("BUY_SIGNAL")
              .nextSetupStatus("TRIGGERED")
              .paperAutoExecute(isPaper(ctx))
              .build();
      return sig;
    }
    return null;
  }

  private TrendWaveSignal evalBreakoutBuy(TrendWaveContext ctx, MoneySetup setup) {
    if (setup.getPlatformHigh() == null) return null;
    BigDecimal price = ctx.getLatestPrice();
    if (price.compareTo(setup.getPlatformHigh()) <= 0) return null;

    boolean volOk = true;
    if (ctx.getMas() != null && ctx.getMas().volMa5() != null) {
      Long vol = ctx.getTodayVolume() != null ? ctx.getTodayVolume() : ctx.getMas().latestVolume();
      if (vol != null) {
        volOk =
            BigDecimal.valueOf(vol)
                    .compareTo(
                        BigDecimal.valueOf(ctx.getMas().volMa5())
                            .multiply(props.getBreakout().getVolumeRatio()))
                >= 0;
      }
    }
    if (!volOk) return null;

    // 尾盘确认：EOD 或 14:50 后
    boolean lateEnough =
        ctx.isEodScan()
            || ctx.getNow() != null
                && (ctx.getNow().getHour() > 14
                    || (ctx.getNow().getHour() == 14 && ctx.getNow().getMinute() >= 50));
    if (!lateEnough) {
      return TrendWaveSignal.builder()
          .eventType("BUY_BREAKOUT_WATCH")
          .severity("WARN")
          .title(title(ctx, "疑似平台突破"))
          .content("放量突破平台高点 " + setup.getPlatformHigh() + "，等待尾盘确认。")
          .triggerPrice(price)
          .triggerData(Map.of("platformHigh", setup.getPlatformHigh()))
          .setupId(setup.getId())
          .mutateState(false)
          .build();
    }

    return TrendWaveSignal.builder()
        .eventType("BUY_BREAKOUT")
        .severity("ACTION")
        .title(title(ctx, "突破买点触发"))
        .content("放量突破平台高点 " + setup.getPlatformHigh() + "，尾盘确认站稳，建议介入。")
        .triggerPrice(price)
        .triggerData(Map.of("platformHigh", setup.getPlatformHigh(), "setupId", setup.getId()))
        .setupId(setup.getId())
        .mutateState(true)
        .nextWatchStatus("BUY_SIGNAL")
        .nextSetupStatus("TRIGGERED")
        .paperAutoExecute(isPaper(ctx))
        .build();
  }

  private void evalBuySignalExpiry(TrendWaveContext ctx, List<TrendWaveSignal> out) {
    if (!"BUY_SIGNAL".equals(ctx.getWatch().getStatus())) return;
    if (ctx.getWatch().getSignalExpireAt() != null
        && ctx.getNow() != null
        && ctx.getNow().isAfter(ctx.getWatch().getSignalExpireAt())) {
      out.add(
          TrendWaveSignal.builder()
              .eventType("BUY_SIGNAL_EXPIRED")
              .severity("INFO")
              .title(title(ctx, "买点信号过期"))
              .content("买点信号超过有效期未建仓，回退观察。")
              .triggerPrice(ctx.getLatestPrice())
              .mutateState(true)
              .nextWatchStatus(
                  "BREAKOUT".equals(ctx.getWatch().getBuySignalType())
                      ? "WATCH_BREAKOUT"
                      : "WATCH_PULLBACK")
              .build());
    }
  }

  private boolean isVolumeDumpOnPullback(TrendWaveContext ctx, MoneySetup setup) {
    if (setup.getPlatformLow() == null || setup.getPlatformOpen() == null) return false;
    BigDecimal zoneHigh = setup.getPlatformLow().max(setup.getPlatformOpen());
    BigDecimal zoneLow = setup.getPlatformLow().min(setup.getPlatformOpen());
    if (ctx.getLatestPrice().compareTo(zoneHigh) > 0) return false;
    if (ctx.getLatestPrice().compareTo(zoneLow.multiply(BigDecimal.valueOf(0.97))) < 0) {
      // already broken
    }
    List<TradeStockDaily> asc = ctx.getDailyAsc();
    if (asc == null || asc.isEmpty()) return false;
    TradeStockDaily last = asc.get(asc.size() - 1);
    if (last.getClosePrice() == null || last.getOpenPrice() == null) return false;
    boolean bearish = last.getClosePrice().compareTo(last.getOpenPrice()) < 0;
    Long volMa5 = ctx.getMas() == null ? null : ctx.getMas().volMa5();
    boolean heavy =
        last.getVolume() != null
            && volMa5 != null
            && BigDecimal.valueOf(last.getVolume())
                    .compareTo(
                        BigDecimal.valueOf(volMa5)
                            .multiply(props.getPullback().getVolumeDumpRatio()))
                >= 0;
    boolean inZone =
        last.getClosePrice().compareTo(zoneHigh) <= 0
            && last.getClosePrice().compareTo(zoneLow.multiply(BigDecimal.valueOf(0.95))) >= 0;
    return ctx.isEodScan() && bearish && heavy && inZone;
  }

  private boolean isIntradayVolumeBreak(TrendWaveContext ctx) {
    if (ctx.getMas() == null || ctx.getMas().volMa5() == null || ctx.getTodayVolume() == null) {
      return false;
    }
    return BigDecimal.valueOf(ctx.getTodayVolume())
            .compareTo(
                BigDecimal.valueOf(ctx.getMas().volMa5())
                    .multiply(props.getStopLoss().getIntradayVolumeBreakRatio()))
        >= 0;
  }

  public Map<String, Object> screenDetail(
      MovingAverages mas,
      BigDecimal highNearRatio,
      boolean sectorOk,
      boolean valuationOk,
      BigDecimal peTtm) {
    Map<String, Object> m = new HashMap<>();
    boolean trend =
        mas != null && mas.aboveMa20() && mas.ma20Rising() && mas.bullishAlignment();
    boolean volume =
        mas != null
            && mas.volRatio() != null
            && mas.volRatio().compareTo(props.getScreening().getVolumeExpandRatio()) >= 0;
    boolean notTooHigh =
        highNearRatio == null
            || highNearRatio.compareTo(props.getScreening().getHighNear3yRatio()) < 0;
    m.put("trend", trend);
    m.put("aboveMa20", mas != null && mas.aboveMa20());
    m.put("ma20Rising", mas != null && mas.ma20Rising());
    m.put("bullishAlignment", mas != null && mas.bullishAlignment());
    m.put("ma5", str(mas == null ? null : mas.ma5()));
    m.put("ma10", str(mas == null ? null : mas.ma10()));
    m.put("ma20", str(mas == null ? null : mas.ma20()));
    m.put("ma60", str(mas == null ? null : mas.ma60()));
    m.put("ma20Slope", str(mas == null ? null : mas.ma20Slope()));
    m.put("volumeExpand", volume);
    m.put("volRatio", str(mas == null ? null : mas.volRatio()));
    m.put("sectorOk", sectorOk);
    m.put("valuationOk", valuationOk);
    m.put("peTtm", str(peTtm));
    m.put("highNearRatio", str(highNearRatio));
    m.put("notTooHigh", notTooHigh);
    m.put(
        "passed",
        trend
            && volume
            && sectorOk
            && valuationOk
            && notTooHigh);
    return m;
  }

  public BigDecimal calcStopPrimary(String buyType, MoneySetup setup, BigDecimal entry) {
    TrendWaveProperties.StopLoss sl = props.getStopLoss();
    if ("BREAKOUT".equals(buyType) && setup != null && setup.getPlatformHigh() != null) {
      return setup
          .getPlatformHigh()
          .multiply(
              BigDecimal.ONE.subtract(
                  sl.getBreakoutPrimaryBufferPct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
          .setScale(2, RoundingMode.HALF_UP);
    }
    if (setup != null && setup.getPlatformLow() != null) {
      return setup
          .getPlatformLow()
          .multiply(
              BigDecimal.ONE.subtract(
                  sl.getPullbackPrimaryBufferPct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
          .setScale(2, RoundingMode.HALF_UP);
    }
    return entry
        .multiply(BigDecimal.ONE.subtract(sl.getPullbackSecondaryPct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
        .setScale(2, RoundingMode.HALF_UP);
  }

  public BigDecimal calcStopSecondary(String buyType, BigDecimal entry) {
    BigDecimal pct =
        "BREAKOUT".equals(buyType)
            ? props.getStopLoss().getBreakoutSecondaryPct()
            : props.getStopLoss().getPullbackSecondaryPct();
    return entry
        .multiply(BigDecimal.ONE.subtract(pct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
        .setScale(2, RoundingMode.HALF_UP);
  }

  public String resolveTier(BigDecimal profitPct, TrendWaveProperties.TakeProfit tp) {
    if (profitPct.compareTo(tp.getTier3ProfitPct()) > 0) return "T3";
    if (profitPct.compareTo(tp.getTier2ProfitPct()) >= 0) return "T2";
    if (profitPct.compareTo(tp.getTier1ProfitPct()) >= 0) return "T1";
    return "T0";
  }

  public BigDecimal drawdownLimit(String tier, TrendWaveProperties.TakeProfit tp) {
    return switch (tier) {
      case "T1" -> tp.getTier1DrawdownPct();
      case "T2" -> tp.getTier2DrawdownPct();
      case "T3" -> tp.getTier3DrawdownPct();
      default -> null;
    };
  }

  public BigDecimal trailingStopPrice(BigDecimal peak, String tier) {
    BigDecimal dd = drawdownLimit(tier, props.getTakeProfit());
    if (peak == null || dd == null) return null;
    return peak
        .multiply(BigDecimal.ONE.subtract(dd.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private MoneySetup activeSetup(TrendWaveContext ctx, String type) {
    if (ctx.getSetups() == null) return null;
    return ctx.getSetups().stream()
        .filter(s -> type.equals(s.getSetupType()) && "ACTIVE".equals(s.getStatus()))
        .findFirst()
        .orElse(null);
  }

  private TrendWaveSignal closeSignal(
      TrendWaveContext ctx,
      String type,
      String shortTitle,
      String content,
      BigDecimal price,
      Map<String, Object> data,
      boolean paper) {
    return TrendWaveSignal.builder()
        .eventType(type)
        .severity("ACTION")
        .title(title(ctx, shortTitle))
        .content(content)
        .triggerPrice(price)
        .triggerData(data)
        .mutateState(true)
        .nextWatchStatus("CLOSED")
        .nextPositionStatus("CLOSED")
        .nextPositionPct(BigDecimal.ZERO)
        .closeReason(type)
        .paperAutoExecute(paper && isPaper(ctx))
        .build();
  }

  private TrendWaveSignal signal(
      String type,
      String severity,
      String title,
      String content,
      BigDecimal price,
      Map<String, Object> data,
      boolean mutate,
      String nextWatch,
      String nextSetup,
      String nextPos,
      BigDecimal nextPct,
      String closeReason,
      boolean paper) {
    return TrendWaveSignal.builder()
        .eventType(type)
        .severity(severity)
        .title(title)
        .content(content)
        .triggerPrice(price)
        .triggerData(data)
        .mutateState(mutate)
        .nextWatchStatus(nextWatch)
        .nextSetupStatus(nextSetup)
        .nextPositionStatus(nextPos)
        .nextPositionPct(nextPct)
        .closeReason(closeReason)
        .paperAutoExecute(paper)
        .build();
  }

  private String title(TrendWaveContext ctx, String action) {
    String name = ctx.getWatch().getStockName() == null ? "" : ctx.getWatch().getStockName();
    return name + "(" + ctx.getWatch().getStockCode() + ") " + action;
  }

  private boolean isHolding(String status) {
    return "HOLDING".equals(status) || "PARTIAL_EXIT".equals(status);
  }

  private boolean isWatching(String status) {
    return "SCREENING".equals(status)
        || "WATCH_PULLBACK".equals(status)
        || "WATCH_BREAKOUT".equals(status);
  }

  private boolean isPaper(TrendWaveContext ctx) {
    return ctx.getPool() != null && integerTrue(ctx.getPool().getPaperMode());
  }

  private BigDecimal effectiveEntry(MoneyPosition pos) {
    if (integerTrue(pos.getAddPositionDone())
        && pos.getAddEntryPrice() != null
        && pos.getEntryShares() != null
        && pos.getAddShares() != null
        && pos.getEntryShares() + pos.getAddShares() > 0) {
      BigDecimal totalCost =
          pos.getEntryPrice()
              .multiply(BigDecimal.valueOf(pos.getEntryShares()))
              .add(pos.getAddEntryPrice().multiply(BigDecimal.valueOf(pos.getAddShares())));
      return totalCost
          .divide(
              BigDecimal.valueOf(pos.getEntryShares() + pos.getAddShares()),
              2,
              RoundingMode.HALF_UP);
    }
    return pos.getEntryPrice();
  }

  private boolean integerTrue(Integer v) {
    return v != null && v == 1;
  }

  private String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  /** 暴露给测试的平台横盘检测。 */
  public boolean isPlatformTighten(List<TradeStockDaily> window, BigDecimal maxRangePct) {
    if (window == null || window.isEmpty()) return false;
    BigDecimal high = null;
    BigDecimal low = null;
    for (TradeStockDaily d : window) {
      if (d.getHighPrice() != null) {
        high = high == null ? d.getHighPrice() : high.max(d.getHighPrice());
      }
      if (d.getLowPrice() != null) {
        low = low == null ? d.getLowPrice() : low.min(d.getLowPrice());
      }
    }
    if (high == null || low == null || low.compareTo(BigDecimal.ZERO) <= 0) return false;
    BigDecimal range =
        high.subtract(low).divide(low, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    return range.compareTo(maxRangePct) <= 0;
  }

  public BigDecimal platformHigh(List<TradeStockDaily> window) {
    return MovingAverageCalculator.sortedAsc(window).stream()
        .map(TradeStockDaily::getHighPrice)
        .filter(v -> v != null)
        .max(BigDecimal::compareTo)
        .orElse(null);
  }
}
