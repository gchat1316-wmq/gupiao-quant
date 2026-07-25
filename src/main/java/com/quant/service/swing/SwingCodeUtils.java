package com.quant.service.swing;

/** 统一为日 K 库格式：600519.SH / 000001.SZ */
public final class SwingCodeUtils {

  private SwingCodeUtils() {}

  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String value = raw.trim().toUpperCase();
    int dot = value.indexOf('.');
    String code;
    String suffix;
    if (dot > 0) {
      code = value.substring(0, dot);
      suffix = value.substring(dot + 1);
      if ("SH".equals(suffix) || "SS".equals(suffix)) {
        suffix = "SH";
      } else if ("SZ".equals(suffix)) {
        suffix = "SZ";
      } else if ("BJ".equals(suffix)) {
        suffix = "BJ";
      } else {
        suffix = inferSuffix(code);
      }
    } else {
      code = value;
      suffix = inferSuffix(code);
    }
    return code + "." + suffix;
  }

  public static String bareCode(String normalized) {
    if (normalized == null) {
      return "";
    }
    int dot = normalized.indexOf('.');
    return dot > 0 ? normalized.substring(0, dot) : normalized;
  }

  private static String inferSuffix(String code) {
    if (code.startsWith("6") || code.startsWith("9")) {
      return "SH";
    }
    if (code.startsWith("8") || code.startsWith("4")) {
      return "BJ";
    }
    return "SZ";
  }
}
