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

/** 每日收盘组合净值快照 — 净值曲线与组合级保命线（回撤20%）依据。 */
@Getter
@Setter
@Entity
@Table(name = "etf_nav_snapshot")
public class EtfNavSnapshot {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "snap_date", nullable = false)
  private LocalDate snapDate;

  @Column(name = "market_value", precision = 14, scale = 2)
  private BigDecimal marketValue;

  @Column(name = "cash", precision = 14, scale = 2)
  private BigDecimal cash;

  @Column(name = "total_asset", precision = 14, scale = 2)
  private BigDecimal totalAsset;

  @Column(name = "peak_asset", precision = 14, scale = 2)
  private BigDecimal peakAsset;

  @Column(name = "drawdown_pct", precision = 8, scale = 2)
  private BigDecimal drawdownPct;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
