package com.quant.service.techai;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class TechAiAlertThresholds {
    private BigDecimal minute1Pct;
    private BigDecimal minute5Pct;
    private BigDecimal dailyPct;
    private BigDecimal threeDayPct;
    private BigDecimal turnoverRatioPct;
}
