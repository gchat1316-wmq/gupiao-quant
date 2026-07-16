package com.quant.dto.journal;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class EquityCurvePoint {
  private Integer tradeIndex; // 1-based ordinal of closed trade
  private Long tradeId;
  private String exitDate; // ISO local date
  private BigDecimal cumulativeR;
}
