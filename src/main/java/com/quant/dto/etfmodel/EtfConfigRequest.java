package com.quant.dto.etfmodel;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/** 模型参数更新请求（字段为 null 表示不修改）。 */
@Data
public class EtfConfigRequest {

  private BigDecimal totalCapital;
  private BigDecimal singleMaxPct;
  private BigDecimal portfolioMaxPct;
  private BigDecimal lightBatchMaxAmount;
  private BigDecimal midBatchMinAmount;
  private BigDecimal midBatchMaxAmount;
  private BigDecimal bigRiseThresholdPct;
  private BigDecimal portfolioDrawdownPct;
  private Integer calmDays;
  private LocalDate inceptionDate;

  /** 传 true 手动解除冷静期 */
  private Boolean clearCalm;
}
