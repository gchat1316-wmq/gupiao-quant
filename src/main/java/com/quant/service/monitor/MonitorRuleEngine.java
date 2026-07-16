package com.quant.service.monitor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.quant.entity.InvestPositionCommon;

/**
 * 纯函数规则引擎：输入 MonitorContext，输出 0..N 个 MonitorSignal。 不做持久化，不做推送 — 调用方负责 dispatch。
 *
 * <p>评估 5 类规则，按优先级顺序短路评估： 1. 固定买入价 (fixed_buy_enabled=1 + latest <= fixedBuyPrice) 2. 固定卖出价
 * (fixed_sell_enabled=1 + latest >= fixedSellPrice) 3. ATR 振幅 (atr_alert_enabled=1 + |latest -
 * openToday| >= atrAlertAmplitude * ATR) 4. 止盈 (entryPrice != null + takeProfitPct != null + latest
 * >= target) 5. %-止损 (entryPrice != null + stopLossPct != null + latest <= floor) 6. ATR 移动止损
 * (useAtr=1 + peakPrice != null + latest <= stopLine)
 */
@Component
public class MonitorRuleEngine {

  public List<MonitorSignal> evaluate(MonitorContext ctx) {
    List<MonitorSignal> signals = new ArrayList<>();
    if (ctx == null || ctx.getLatest() == null) {
      return signals;
    }
    evalFixedPrice(ctx, signals);
    evalAtrAmplitude(ctx, signals);
    evalTakeProfit(ctx, signals);
    evalStopLossPct(ctx, signals);
    evalStopLossAtr(ctx, signals);
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

  private void evalStopLossAtr(MonitorContext ctx, List<MonitorSignal> out) {
    InvestPositionCommon pos = ctx.getPosition();
    if (pos == null) return;
    if (!Integer.valueOf(1).equals(pos.getUseAtr())) return;
    if (pos.getPeakPrice() == null || pos.getAtrTrailMult() == null || ctx.getAtr() == null) return;
    BigDecimal stopLine =
        pos.getPeakPrice()
            .subtract(pos.getAtrTrailMult().multiply(ctx.getAtr()).multiply(BigDecimal.valueOf(2)));
    if (ctx.getLatest().compareTo(stopLine) <= 0) {
      out.add(
          MonitorSignal.stopLossAtr(
              pos, ctx.getStockCode(), ctx.getStockName(), ctx.getLatest(), stopLine));
    }
  }

  private static boolean intAsBool(Integer i) {
    return i != null && i == 1;
  }
}
