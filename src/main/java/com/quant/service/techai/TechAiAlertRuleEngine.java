package com.quant.service.techai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class TechAiAlertRuleEngine {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  public List<TechAiAlertCandidate> evaluate(TechAiMarketContext ctx) {
    return evaluate(ctx, null);
  }

  public List<TechAiAlertCandidate> evaluate(
      TechAiMarketContext ctx, TechAiAlertThresholds thresholds) {
    List<TechAiAlertCandidate> alerts = new ArrayList<>();
    BigDecimal latest = ctx.getLatestPrice();
    if (latest == null) {
      return alerts;
    }
    addChangeAlerts(
        alerts,
        ctx,
        "minute_1m",
        "1分钟涨跌幅",
        latest,
        ctx.getMinute1OpenPrice(),
        thresholds == null
            ? List.of(BigDecimal.valueOf(3))
            : thresholdList(valueOrDefault(thresholds.getMinute1Pct(), BigDecimal.valueOf(3))),
        true);
    addChangeAlerts(
        alerts,
        ctx,
        "minute_5m",
        "5分钟涨跌幅",
        latest,
        ctx.getMinute5OpenPrice(),
        thresholds == null
            ? List.of(BigDecimal.valueOf(5))
            : thresholdList(valueOrDefault(thresholds.getMinute5Pct(), BigDecimal.valueOf(5))),
        true);
    addChangeAlerts(
        alerts,
        ctx,
        "daily",
        "当日涨跌幅",
        latest,
        ctx.getPrevClosePrice(),
        thresholds == null
            ? List.of(BigDecimal.valueOf(3), BigDecimal.valueOf(5), BigDecimal.valueOf(7))
            : thresholdList(valueOrDefault(thresholds.getDailyPct(), BigDecimal.valueOf(3))),
        false);
    addTurnoverAlerts(
        alerts,
        ctx,
        thresholds == null
            ? List.of(BigDecimal.valueOf(150), BigDecimal.valueOf(200), BigDecimal.valueOf(300))
            : thresholdList(
                valueOrDefault(thresholds.getTurnoverRatioPct(), BigDecimal.valueOf(150))));
    addChangeAlerts(
        alerts,
        ctx,
        "three_day",
        "3日涨跌幅",
        latest,
        ctx.getClosePrice3TradingDaysAgo(),
        thresholds == null
            ? List.of(BigDecimal.valueOf(10), BigDecimal.valueOf(15), BigDecimal.valueOf(20))
            : thresholdList(valueOrDefault(thresholds.getThreeDayPct(), BigDecimal.valueOf(10))),
        false);
    return alerts;
  }

  private BigDecimal valueOrDefault(BigDecimal value, BigDecimal fallback) {
    return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? fallback : value;
  }

  private List<BigDecimal> thresholdList(BigDecimal threshold) {
    return List.of(threshold);
  }

  private void addChangeAlerts(
      List<TechAiAlertCandidate> alerts,
      TechAiMarketContext ctx,
      String rulePrefix,
      String label,
      BigDecimal latest,
      BigDecimal base,
      List<BigDecimal> thresholds,
      boolean minuteRule) {
    BigDecimal change = pctChange(latest, base);
    if (change == null) {
      return;
    }
    for (BigDecimal threshold : thresholds) {
      if (change.compareTo(threshold) >= 0) {
        alerts.add(candidate(ctx, rulePrefix + "_up", "up", threshold, change, label, minuteRule));
      }
      BigDecimal negative = threshold.negate();
      if (change.compareTo(negative) <= 0) {
        alerts.add(
            candidate(ctx, rulePrefix + "_down", "down", negative, change, label, minuteRule));
      }
    }
  }

  private void addTurnoverAlerts(
      List<TechAiAlertCandidate> alerts, TechAiMarketContext ctx, List<BigDecimal> thresholds) {
    BigDecimal current = ctx.getTurnoverRate();
    BigDecimal avg = ctx.getAvgTurnoverRate5d();
    if (current == null || avg == null || avg.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    BigDecimal ratio = current.divide(avg, 6, RoundingMode.HALF_UP).multiply(HUNDRED);
    for (BigDecimal threshold : thresholds) {
      if (ratio.compareTo(threshold) >= 0) {
        alerts.add(candidate(ctx, "turnover_rate", "up", threshold, ratio, "换手率放大", false));
      }
    }
  }

  private BigDecimal pctChange(BigDecimal value, BigDecimal base) {
    if (value == null || base == null || base.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    return value
        .subtract(base)
        .divide(base, 6, RoundingMode.HALF_UP)
        .multiply(HUNDRED)
        .setScale(2, RoundingMode.HALF_UP);
  }

  private TechAiAlertCandidate candidate(
      TechAiMarketContext ctx,
      String ruleType,
      String direction,
      BigDecimal threshold,
      BigDecimal currentValue,
      String label,
      boolean minuteRule) {
    String sign = threshold.compareTo(BigDecimal.ZERO) > 0 ? "+" : "";
    String title =
        String.format(
            "%s(%s) %s 触发 %s%%",
            ctx.getStockName(),
            ctx.getStockCode(),
            label,
            sign + threshold.stripTrailingZeros().toPlainString());
    String content =
        String.format(
            """
                ## %s（%s）

                **触发规则**：%s

                **当前值**：%s%%

                **阈值**：%s%s%%

                **当前价**：%s

                **成交量**：%s

                **换手率**：%s%%

                **行情时间**：%s
                """,
            ctx.getStockName(),
            ctx.getStockCode(),
            label,
            currentValue.stripTrailingZeros().toPlainString(),
            sign,
            threshold.stripTrailingZeros().toPlainString(),
            ctx.getLatestPrice(),
            ctx.getVolume() == null ? "-" : ctx.getVolume(),
            ctx.getTurnoverRate() == null
                ? "-"
                : ctx.getTurnoverRate().stripTrailingZeros().toPlainString(),
            ctx.getQuoteTime() == null ? "-" : ctx.getQuoteTime());
    return new TechAiAlertCandidate(
        ctx.getStockCode(),
        ctx.getStockName(),
        ruleType,
        direction,
        threshold,
        currentValue,
        title,
        content,
        minuteRule);
  }
}
