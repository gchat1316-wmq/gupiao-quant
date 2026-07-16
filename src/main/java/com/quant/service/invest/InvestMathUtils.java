package com.quant.service.invest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 投资池 / SOP 体检共用的纯计算工具：同比、景气度分档、季度标签、数值解析。
 *
 * <p>无状态、无依赖，全部为静态方法，供 {@link InvestService}、{@link InvestPoolService}、{@link
 * InvestSopService}、{@link InvestValuationService} 复用，避免各处重复实现导致算法漂移。
 */
public final class InvestMathUtils {

  private InvestMathUtils() {}

  /** 同比增速 (%) = (current - prev) / |prev| × 100；任一为 null 或 prev=0 时返回 null。 */
  public static BigDecimal calcYoy(BigDecimal current, BigDecimal prev) {
    if (current == null || prev == null || prev.compareTo(BigDecimal.ZERO) == 0) return null;
    return current
        .subtract(prev)
        .divide(prev.abs(), 4, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
  }

  /** 依据营收同比划分景气度：≥30 high / ≥5 medium / ≥0 weak / 其余 low；null → unknown。 */
  public static String prosperityLevel(BigDecimal yoy) {
    if (yoy == null) return "unknown";
    double v = yoy.doubleValue();
    if (v >= 30) return "high";
    if (v >= 5) return "medium";
    if (v >= 0) return "weak";
    return "low";
  }

  /** 把报告期日期格式化为 "yyQn"（如 2027-03-31 → 27Q1）。 */
  public static String formatQuarter(LocalDate d) {
    int year = d.getYear() % 100;
    int q =
        switch (d.getMonthValue()) {
          case 3 -> 1;
          case 6 -> 2;
          case 9 -> 3;
          case 12 -> 4;
          default -> (d.getMonthValue() - 1) / 3 + 1;
        };
    return String.format("%02dQ%d", year, q);
  }

  /** 解析用户输入的数值字符串：空白 → null；非法格式抛 IllegalArgumentException。 */
  public static BigDecimal parseDecimal(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("数值格式错误：" + raw);
    }
  }
}
