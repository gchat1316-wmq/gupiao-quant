package com.quant.service.monitor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.quant.entity.InvestPositionCommon;

import lombok.Builder;
import lombok.Data;

/** 一次监控命中事件，从 RuleEngine 产出。包含渲染所需的全部素材。 */
@Data
@Builder
public class MonitorSignal {

  public static final String FIXED_BUY = "fixed_buy_hit";
  public static final String FIXED_SELL = "fixed_sell_hit";
  public static final String ATR_AMPLITUDE = "atr_amplitude_alert";
  public static final String TAKE_PROFIT = "take_profit_hit";
  public static final String STOP_LOSS_PCT = "stop_loss_hit";
  public static final String STOP_LOSS_ATR = "stop_loss_atr_hit";
  public static final String DAILY_PCT = "daily_pct_alert";
  public static final String THREE_DAY_PCT = "three_day_pct_alert";
  public static final String MINUTE_1M_PCT = "minute_1m_pct_alert";
  public static final String MINUTE_5M_PCT = "minute_5m_pct_alert";
  public static final String TURNOVER_RATIO = "turnover_ratio_alert";

  private String stockCode;
  private String stockName;
  private String signalType;
  private String title;
  private String content;
  private BigDecimal triggerPrice;
  private BigDecimal threshold;
  private BigDecimal currentValue;
  private String template;
  private LocalDateTime triggeredAt;

  public static MonitorSignal fixedPriceBuy(
      InvestPositionCommon pos,
      String code,
      String name,
      BigDecimal triggerPrice,
      BigDecimal threshold) {
    return MonitorSignal.builder()
        .stockCode(code)
        .stockName(name)
        .signalType(FIXED_BUY)
        .title(String.format("📉 %s(%s) 触及买入价 %s", name, code, threshold))
        .content(
            "## "
                + name
                + "（"
                + code
                + "）\n\n- 当前价: "
                + triggerPrice
                + "\n- 固定买入价 "
                + threshold
                + " ✅ 已触发")
        .triggerPrice(triggerPrice)
        .threshold(threshold)
        .template(pos.getServerchanTemplate() == null ? "standard" : pos.getServerchanTemplate())
        .triggeredAt(LocalDateTime.now())
        .build();
  }

  public static MonitorSignal fixedPriceSell(
      InvestPositionCommon pos,
      String code,
      String name,
      BigDecimal triggerPrice,
      BigDecimal threshold) {
    return MonitorSignal.builder()
        .stockCode(code)
        .stockName(name)
        .signalType(FIXED_SELL)
        .title(String.format("📈 %s(%s) 触及卖出价 %s", name, code, threshold))
        .content(
            "## "
                + name
                + "（"
                + code
                + "）\n\n- 当前价: "
                + triggerPrice
                + "\n- 固定卖出价 "
                + threshold
                + " ✅ 已触发")
        .triggerPrice(triggerPrice)
        .threshold(threshold)
        .template(pos.getServerchanTemplate() == null ? "standard" : pos.getServerchanTemplate())
        .triggeredAt(LocalDateTime.now())
        .build();
  }

  public static MonitorSignal atrAmplitude(
      InvestPositionCommon pos,
      String code,
      String name,
      BigDecimal latest,
      BigDecimal openToday,
      BigDecimal atr,
      BigDecimal mult) {
    return MonitorSignal.builder()
        .stockCode(code)
        .stockName(name)
        .signalType(ATR_AMPLITUDE)
        .title(
            String.format(
                "📊 %s(%s) 振幅达 %sx ATR", name, code, mult.stripTrailingZeros().toPlainString()))
        .content(
            "## "
                + name
                + "（"
                + code
                + "）\n\n"
                + "- 当前价: "
                + latest
                + "\n"
                + "- 开盘价: "
                + openToday
                + "\n"
                + "- ATR: "
                + atr
                + "\n"
                + "- 振幅阈值: "
                + mult
                + "x ATR ✅ 已触发")
        .triggerPrice(latest)
        .threshold(mult)
        .currentValue(atr)
        .template(pos.getServerchanTemplate() == null ? "standard" : pos.getServerchanTemplate())
        .triggeredAt(LocalDateTime.now())
        .build();
  }

  public static MonitorSignal takeProfit(
      InvestPositionCommon pos,
      String code,
      String name,
      BigDecimal triggerPrice,
      BigDecimal entryPrice,
      BigDecimal pct) {
    return MonitorSignal.builder()
        .stockCode(code)
        .stockName(name)
        .signalType(TAKE_PROFIT)
        .title(
            String.format(
                "💰 %s(%s) 触发止盈 +%s%%", name, code, pct.stripTrailingZeros().toPlainString()))
        .content(
            "## "
                + name
                + "（"
                + code
                + "）\n\n"
                + "- 当前价: "
                + triggerPrice
                + "\n"
                + "- 成本价: "
                + entryPrice
                + "\n"
                + "- 止盈幅度: +"
                + pct
                + "%\n")
        .triggerPrice(triggerPrice)
        .threshold(pct)
        .template(pos.getServerchanTemplate() == null ? "standard" : pos.getServerchanTemplate())
        .triggeredAt(LocalDateTime.now())
        .build();
  }

  public static MonitorSignal stopLossPct(
      InvestPositionCommon pos,
      String code,
      String name,
      BigDecimal triggerPrice,
      BigDecimal entryPrice,
      BigDecimal stopPct) {
    return MonitorSignal.builder()
        .stockCode(code)
        .stockName(name)
        .signalType(STOP_LOSS_PCT)
        .title(
            String.format(
                "🛑 %s(%s) 触发止损 %s%%", name, code, stopPct.stripTrailingZeros().toPlainString()))
        .content(
            "## "
                + name
                + "（"
                + code
                + "）\n\n"
                + "- 当前价: "
                + triggerPrice
                + "\n"
                + "- 成本价: "
                + entryPrice
                + "\n"
                + "- 止损幅度: "
                + stopPct
                + "%\n")
        .triggerPrice(triggerPrice)
        .threshold(stopPct)
        .template(pos.getServerchanTemplate() == null ? "standard" : pos.getServerchanTemplate())
        .triggeredAt(LocalDateTime.now())
        .build();
  }

  public static MonitorSignal stopLossAtr(
      InvestPositionCommon pos,
      String code,
      String name,
      BigDecimal triggerPrice,
      BigDecimal stopLine) {
    return MonitorSignal.builder()
        .stockCode(code)
        .stockName(name)
        .signalType(STOP_LOSS_ATR)
        .title(String.format("🛑 %s(%s) 触发 ATR 移动止损 %s", name, code, stopLine))
        .content(
            "## "
                + name
                + "（"
                + code
                + "）\n\n"
                + "- 当前价: "
                + triggerPrice
                + "\n"
                + "- ATR 移动止损位: "
                + stopLine
                + " ✅ 已触发")
        .triggerPrice(triggerPrice)
        .threshold(stopLine)
        .template(pos.getServerchanTemplate() == null ? "standard" : pos.getServerchanTemplate())
        .triggeredAt(LocalDateTime.now())
        .build();
  }

  public static MonitorSignal pctMove(
      InvestPositionCommon pos,
      String code,
      String name,
      String signalType,
      String label,
      BigDecimal triggerPrice,
      BigDecimal thresholdPct,
      BigDecimal currentPct) {
    String dir = currentPct != null && currentPct.compareTo(BigDecimal.ZERO) >= 0 ? "上涨" : "下跌";
    return MonitorSignal.builder()
        .stockCode(code)
        .stockName(name)
        .signalType(signalType)
        .title(
            String.format(
                "📊 %s(%s) %s %s%%（阈值 %s%%）",
                name,
                code,
                label + dir,
                currentPct == null ? "?" : currentPct.stripTrailingZeros().toPlainString(),
                thresholdPct == null ? "?" : thresholdPct.stripTrailingZeros().toPlainString()))
        .content(
            "## "
                + name
                + "（"
                + code
                + "）\n\n"
                + "- 当前价: "
                + triggerPrice
                + "\n"
                + "- "
                + label
                + ": "
                + currentPct
                + "%\n"
                + "- 阈值: ±"
                + thresholdPct
                + "% ✅ 已触发")
        .triggerPrice(triggerPrice)
        .threshold(thresholdPct)
        .currentValue(currentPct)
        .template(pos.getServerchanTemplate() == null ? "standard" : pos.getServerchanTemplate())
        .triggeredAt(LocalDateTime.now())
        .build();
  }
}
