package com.quant.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 省心 ETF 模型全局参数（单行, id=1）。 */
@Getter
@Setter
@Entity
@Table(name = "etf_model_config")
public class EtfModelConfig {

  @Id private Long id;

  @Column(name = "total_capital", precision = 14, scale = 2)
  private BigDecimal totalCapital;

  @Column(name = "single_max_pct", precision = 5, scale = 2)
  private BigDecimal singleMaxPct;

  @Column(name = "portfolio_max_pct", precision = 5, scale = 2)
  private BigDecimal portfolioMaxPct;

  @Column(name = "light_batch_max_amount", precision = 14, scale = 2)
  private BigDecimal lightBatchMaxAmount;

  @Column(name = "mid_batch_min_amount", precision = 14, scale = 2)
  private BigDecimal midBatchMinAmount;

  @Column(name = "mid_batch_max_amount", precision = 14, scale = 2)
  private BigDecimal midBatchMaxAmount;

  @Column(name = "big_rise_threshold_pct", precision = 5, scale = 2)
  private BigDecimal bigRiseThresholdPct;

  @Column(name = "portfolio_drawdown_pct", precision = 5, scale = 2)
  private BigDecimal portfolioDrawdownPct;

  @Column(name = "calm_days")
  private Integer calmDays;

  @Column(name = "inception_date")
  private LocalDate inceptionDate;

  @Column(name = "nav_peak", precision = 14, scale = 2)
  private BigDecimal navPeak;

  @Column(name = "nav_peak_date")
  private LocalDate navPeakDate;

  @Column(name = "calm_until")
  private LocalDate calmUntil;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
