package com.quant.service.monitor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.quant.entity.InvestPositionCommon;

/**
 * 纯函数规则引擎：输入 MonitorContext，输出 0..N 个 MonitorSignal。 不做持久化，不做推送 — 调用方负责 dispatch。
 *
 * <p>支持 monitorMode：
 *
 * <ul>
 *   <li>{@code standard}：全部规则
 *   <li>{@code fixed_only}：仅固定买/卖价
 *   <li>{@code atr_strict}：仅 ATR 振幅 + ATR 移动止损
 * </ul>
 */
@Component
public class MonitorRuleEngine {

  public static final String MODE_STANDARD = "standard";
  public static final String MODE_FIXED_ONLY = "fixed_only";
  public static final String MODE_ATR_STRICT = "atr_strict";

  public List<MonitorSignal> evaluate(MonitorContext ctx) {
    List<MonitorSignal> signals = new ArrayList<>();
    if (ctx == null || ctx.getLatest() == null) {
      return signals;
    }
    String mode = normalizeMode(ctx.getPosition() == null ? null : ctx.getPosition().getMonitorMode());
    boolean fixedOk = MODE_STANDARD.equals(mode) || MODE_FIXED_ONLY.equals(mode);
    boolean atrOk = MODE_STANDARD.equals(mode) || MODE_ATR_STRICT.equals(mode);
    boolean pctOk = MODE_STANDARD.equals(mode);

    if (fixedOk) {
      evalFixedPrice(ctx, signals);
    }
    if (atrOk) {
      evalAtrAmplitude(ctx, signals);
      evalStopLossAtr(ctx, signals);
    }
    if (pctOk) {
      evalTakeProfit(ctx, signals);
      evalStopLossPct(ctx, signals);
      evalDailyPct(ctx, signals);
      evalThreeDayPct(ctx, signals);
      evalMinutePct(ctx, signals, true);
      evalMinutePct(ctx, signals, false);
      evalTurnoverRatio(ctx, signals);
    }
    return signals;
  }

  private void evalFixedPrice(MonitorContext ctx, List<MonitorSignal> out) {
    InvestPositionCommon pos = ctx.getPosition();
    if (pos == null) return;
    BigDecimal latest = ctx.getLatest();
    if (intAsBool(pos.getFixedBuyEnabled())
        && pos.getFixedBuyPrice() != null
        && latest.compareTo(pos.getFixedBuyPrice()) <= 0) {
      out.add(
          MonitorSignal.fixedPriceBuy(
              pos, ctx.getStockCode(), ctx.getStockName(), latest, pos.getFixedBuyPrice()));
    }
    if (intAsBool(pos.getFixedSellEnabled())
        && pos.getFixedSellPrice() != null
        && latest.compareTo(pos.getFixedSellPrice()) >= 0) {
      out.add(
          MonitorSignal.fixedPriceSell(
              pos, ctx.getStockCode(), ctx.getStockName(), latest, pos.getFixedSellPrice()));
    }
  }

  private void evalAtrAmplitude(MonitorContext ctx, List<MonitorSignal> out) {
    InvestPositionCommon pos = ctx.getPosition();
    if (pos == null) return;
    if (!intAsBool(pos.getAtrAlertEnabled())) return;
    if (pos.getAtrAlertAmplitude() == null || ctx.getAtr() == null || ctx.getOpenToday() == null)
      return;
    BigDecimal move = ctx.getLatest().subtract(ctx.getOpenToday()).abs();
    BigDecimal threshold = pos.getAtrAlertAmplitude().multiply(ctx.getAtr());
    if (move.compareTo(threshold) >= 0) {
      out.add(
          MonitorSignal.atrAmplitude(
              pos,
              ctx.getStockCode(),
              ctx.getStockName(),
              ctx.getLatest(),
              ctx.getOpenToday(),
              ctx.getAtr(),
              pos.getAtrAlertAmplitude()));
    }
  }

  private void evalTakeProfit(MonitorContext ctx, List<MonitorSignal> out) {
    InvestPositionCommon pos = ctx.getPosition();
    if (pos == null || pos.getTakeProfitPct() == null || pos.getEntryPrice() == null) return;
    if (pos.getTakeProfitPct().compareTo(BigDecimal.ZERO) <= 0) return;
    BigDecimal target =
        pos.getEntryPrice()
            .multiply(
                BigDecimal.ONE.add(
                    pos.getTakeProfitPct()
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
    if (ctx.getLatest().compareTo(target) >= 0) {
      out.add(
          MonitorSignal.takeProfit(
              pos,
              ctx.getStockCode(),
              ctx.getStockName(),
              ctx.getLatest(),
              pos.getEntryPrice(),
              pos.getTakeProfitPct()));
    }
  }

  private void evalStopLossPct(MonitorContext ctx, List<MonitorSignal> out) {
    InvestPositionCommon pos = ctx.getPosition();
    if (pos == null || pos.getStopLossPct() == null || pos.getEntryPrice() == null) return;
    if (pos.getStopLossPct().compareTo(BigDecimal.ZERO) >= 0) return;
    BigDecimal mult =
        BigDecimal.ONE.add(
            pos.getStopLossPct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
    BigDecimal floor = pos.getEntryPrice().multiply(mult);
    if (ctx.getLatest().compareTo(floor) <= 0) {
      out.add(
          MonitorSignal.stopLossPct(
              pos,
              ctx.getStockCode(),
              ctx.getStockName(),
              ctx.getLatest(),
              pos.getEntryPrice(),
              pos.getStopLossPct()));
    }
  }

  /**
   * ATR 移动止损：stopLine = peak - atrTrailMult * ATR。 与 {@code TechAiPositionEngine} 一致：atrTrailMult
   * 缺失时由调用方跳过；不再额外硬乘 2。
   */
  private void evalStopLossAtr(MonitorContext ctx, List<MonitorSignal> out) {
    InvestPositionCommon pos = ctx.getPosition();
    if (pos == null) return;
    if (!Integer.valueOf(1).equals(pos.getUseAtr())) return;
    if (pos.getPeakPrice() == null || pos.getAtrTrailMult() == null || ctx.getAtr() == null) return;
    BigDecimal stopLine =
        pos.getPeakPrice().subtract(pos.getAtrTrailMult().multiply(ctx.getAtr()));
    if (ctx.getLatest().compareTo(stopLine) <= 0) {
      out.add(
          MonitorSignal.stopLossAtr(
              pos, ctx.getStockCode(), ctx.getStockName(), ctx.getLatest(), stopLine));
    }
  }

  private void evalDailyPct(MonitorContext ctx, List<MonitorSignal> out) {
    InvestPositionCommon pos = ctx.getPosition();
    if (pos == null || pos.getAlertDailyPct() == null || ctx.getPrevClose() == null) return;
    BigDecimal change = pctChange(ctx.getLatest(), ctx.getPrevClose());
    if (change == null) return;
    BigDecimal th = pos.getAlertDailyPct().abs();
    if (th.compareTo(BigDecimal.ZERO) <= 0) return;
    if (change.abs().compareTo(th) >= 0) {
      out.add(
          MonitorSignal.pctMove(
              pos,
              ctx.getStockCode(),
              ctx.getStockName(),
              MonitorSignal.DAILY_PCT,
              "当日涨跌幅",
              ctx.getLatest(),
              th,
              change));
    }
  }

  private void evalThreeDayPct(MonitorContext ctx, List<MonitorSignal> out) {
    InvestPositionCommon pos = ctx.getPosition();
    if (pos == null || pos.getAlertThreeDayPct() == null || ctx.getClosePrice3DaysAgo() == null)
      return;
    BigDecimal change = pctChange(ctx.getLatest(), ctx.getClosePrice3DaysAgo());
    if (change == null) return;
    BigDecimal th = pos.getAlertThreeDayPct().abs();
    if (th.compareTo(BigDecimal.ZERO) <= 0) return;
    if (change.abs().compareTo(th) >= 0) {
      out.add(
          MonitorSignal.pctMove(
              pos,
              ctx.getStockCode(),
              ctx.getStockName(),
              MonitorSignal.THREE_DAY_PCT,
              "3日涨跌幅",
              ctx.getLatest(),
              th,
              change));
    }
  }

  private void evalMinutePct(MonitorContext ctx, List<MonitorSignal> out, boolean oneMinute) {
    InvestPositionCommon pos = ctx.getPosition();
    if (pos == null) return;
    BigDecimal threshold =
        oneMinute ? pos.getAlertMinute1mPct() : pos.getAlertMinute5mPct();
    BigDecimal base = oneMinute ? ctx.getMinute1Open() : ctx.getMinute5Open();
    if (threshold == null || base == null) return;
    BigDecimal change = pctChange(ctx.getLatest(), base);
    if (change == null) return;
    BigDecimal th = threshold.abs();
    if (th.compareTo(BigDecimal.ZERO) <= 0) return;
    if (change.abs().compareTo(th) >= 0) {
      out.add(
          MonitorSignal.pctMove(
              pos,
              ctx.getStockCode(),
              ctx.getStockName(),
              oneMinute ? MonitorSignal.MINUTE_1M_PCT : MonitorSignal.MINUTE_5M_PCT,
              oneMinute ? "1分钟涨跌幅" : "5分钟涨跌幅",
              ctx.getLatest(),
              th,
              change));
    }
  }

  private void evalTurnoverRatio(MonitorContext ctx, List<MonitorSignal> out) {
    InvestPositionCommon pos = ctx.getPosition();
    if (pos == null
        || pos.getAlertTurnoverRatioPct() == null
        || ctx.getTurnoverRate() == null
        || ctx.getAvgTurnoverRate5d() == null) return;
    if (ctx.getAvgTurnoverRate5d().compareTo(BigDecimal.ZERO) <= 0) return;
    BigDecimal ratio =
        ctx.getTurnoverRate()
            .divide(ctx.getAvgTurnoverRate5d(), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    BigDecimal th = pos.getAlertTurnoverRatioPct().abs();
    if (th.compareTo(BigDecimal.ZERO) <= 0) return;
    if (ratio.compareTo(th) >= 0) {
      out.add(
          MonitorSignal.pctMove(
              pos,
              ctx.getStockCode(),
              ctx.getStockName(),
              MonitorSignal.TURNOVER_RATIO,
              "换手率放大",
              ctx.getLatest(),
              th,
              ratio));
    }
  }

  static String normalizeMode(String raw) {
    if (raw == null || raw.isBlank()) return MODE_STANDARD;
    String m = raw.trim().toLowerCase(Locale.ROOT);
    return switch (m) {
      case MODE_FIXED_ONLY, MODE_ATR_STRICT, MODE_STANDARD -> m;
      default -> MODE_STANDARD;
    };
  }

  private static BigDecimal pctChange(BigDecimal value, BigDecimal base) {
    if (value == null || base == null || base.compareTo(BigDecimal.ZERO) == 0) return null;
    return value
        .subtract(base)
        .divide(base, 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static boolean intAsBool(Integer i) {
    return i != null && i == 1;
  }
}
