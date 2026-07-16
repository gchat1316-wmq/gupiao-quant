package com.quant.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "prosperity_leader_candidate")
public class ProsperityLeaderCandidate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(name = "snap_date", nullable = false)
  private LocalDate snapDate;

  @Column(name = "sector_id", nullable = false)
  private Integer sectorId;

  @Column(name = "sector_name", nullable = false, length = 64)
  private String sectorName;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "stock_name", length = 50)
  private String stockName;

  @Column(name = "leader_score", precision = 8, scale = 2)
  private BigDecimal leaderScore;

  @Column(name = "ytd_change", precision = 8, scale = 4)
  private BigDecimal ytdChange;

  @Column(name = "change_5d", precision = 8, scale = 4)
  private BigDecimal change5d;

  @Column(name = "turnover_rate", precision = 8, scale = 4)
  private BigDecimal turnoverRate;

  @Column(name = "main_inflow_5d", precision = 20, scale = 2)
  private BigDecimal mainInflow5d;

  @Column(name = "filter_passed")
  private Integer filterPassed;

  @Column(name = "filter_reason", length = 128)
  private String filterReason;

  @Column(name = "finance_score", precision = 8, scale = 2)
  private BigDecimal financeScore;

  @Column(name = "finance_passed")
  private Integer financePassed;

  @Column(name = "finance_reason", length = 256)
  private String financeReason;

  @Column(name = "mainline_score", precision = 8, scale = 2)
  private BigDecimal mainlineScore;

  @Column(name = "mainline_passed")
  private Integer mainlinePassed;

  @Column(name = "mainline_reason", length = 256)
  private String mainlineReason;

  @Column(name = "final_stage", length = 20)
  private String finalStage;

  @Column(name = "revenue_yoy_min_4q", precision = 10, scale = 4)
  private BigDecimal revenueYoyMin4q;

  @Column(name = "deducted_netprofit_yoy_min_4q", precision = 10, scale = 4)
  private BigDecimal deductedNetProfitYoyMin4q;

  @Column(name = "gross_margin_avg_4q", precision = 10, scale = 4)
  private BigDecimal grossMarginAvg4q;

  @Column(name = "debt_ratio_latest", precision = 10, scale = 4)
  private BigDecimal debtRatioLatest;

  @Column(name = "operating_cashflow_sum_4q", precision = 20, scale = 2)
  private BigDecimal operatingCashflowSum4q;

  @Column(name = "roe_latest", precision = 10, scale = 4)
  private BigDecimal roeLatest;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
