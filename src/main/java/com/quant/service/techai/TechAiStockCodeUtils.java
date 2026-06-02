package com.quant.service.techai;

public final class TechAiStockCodeUtils {

    private TechAiStockCodeUtils() {
    }

    public static String normalizeProjectCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        String code = value;
        String suffix = null;
        int dot = value.indexOf('.');
        if (dot > 0) {
            code = value.substring(0, dot);
            suffix = value.substring(dot + 1);
        }
        code = code.trim();
        if (suffix == null || suffix.isBlank()) {
            suffix = inferSuffix(code);
        }
        return code + "." + suffix.toLowerCase();
    }

    public static String toQmtCode(String projectCode) {
        String normalized = normalizeProjectCode(projectCode);
        int dot = normalized.indexOf('.');
        if (dot < 0) {
            return normalized;
        }
        return normalized.substring(0, dot) + "." + normalized.substring(dot + 1).toUpperCase();
    }

    private static String inferSuffix(String code) {
        if (code.startsWith("6") || code.startsWith("9")) {
            return "sh";
        }
        return "sz";
    }
}
