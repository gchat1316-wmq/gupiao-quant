package com.quant.service.practical;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 实战选股 · 无状态工具（四舍五入 / 空值兜底 / 星级文本 / JSON 抽取）。 */
public final class PracticalSelectSupport {

  private PracticalSelectSupport() {}

  /** null -> 0，否则取 doubleValue。 */
  public static double nullSafe(BigDecimal v) {
    return v == null ? 0 : v.doubleValue();
  }

  /** null -> 0，否则原值。 */
  public static double nullSafe(Double v) {
    return v == null ? 0 : v;
  }

  /** 保留 2 位小数（HALF_UP），null 透传。 */
  public static Double round2(Double v) {
    if (v == null) return null;
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  /** 保留 2 位小数（HALF_UP），null 透传。 */
  public static Double round2(BigDecimal v) {
    if (v == null) return null;
    return v.setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  /** 星级数值 -> ★/☆ 文本（满 5 位），null -> "—"。 */
  public static String starsToText(Double stars) {
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

  /** 从可能带 Markdown 包裹或前后文本的字符串中抽取最外层 JSON 对象，失败返回 null。 */
  public static String extractJsonBlock(String text) {
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
}
