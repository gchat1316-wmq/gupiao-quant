package com.quant.service.techai;

import java.math.BigDecimal;

public record TechAiAlertCandidate(
        String stockCode,
        String stockName,
        String ruleType,
        String direction,
        BigDecimal threshold,
        BigDecimal currentValue,
        String title,
        String content,
        boolean minuteRule
) {
    public String dedupeKey() {
        return stockCode + "|" + ruleType + "|" + threshold.stripTrailingZeros().toPlainString();
    }
}
