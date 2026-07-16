package com.quant.service.techai;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TechAiAlertThresholds {
  private BigDecimal minute1Pct;
  private BigDecimal minute5Pct;
  private BigDecimal dailyPct;
  private BigDecimal threeDayPct;
  private BigDecimal turnoverRatioPct;
}
