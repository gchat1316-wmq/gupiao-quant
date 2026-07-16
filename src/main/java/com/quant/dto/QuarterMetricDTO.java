package com.quant.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class QuarterMetricDTO {
  private String quarter;
  private String reportDate;
  // 核心增长
  private BigDecimal revenueYoy;
  private BigDecimal deductedNetProfitYoy;
  // 盈利能力
  private BigDecimal grossMargin;
  private BigDecimal netMargin;
  private BigDecimal roe;
  private BigDecimal roa;
  private BigDecimal eps;
  // 规模
  private BigDecimal revenue;
  private BigDecimal netProfit;
  private BigDecimal deductedNetProfitTtm;
  private BigDecimal totalAssets;
  private BigDecimal totalEquity;
  // 现金流
  private BigDecimal operatingCashflow;
  // 风险
  private BigDecimal debtRatio;
  private BigDecimal currentRatio;
}
