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
@Table(name = "invest_big_yang_signal")
public class InvestBigYangSignal {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_pool_id")
  private Integer sourcePoolId;

  @Column(name = "source_pool_type", length = 20)
  private String sourcePoolType;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "stock_name", nullable = false, length = 64)
  private String stockName;

  @Column(name = "signal_status", nullable = false, length = 20)
  private String signalStatus;

  @Column(name = "limit_up_streak", nullable = false)
  private Integer limitUpStreak;

  @Column(name = "first_limit_up_date", nullable = false)
  private LocalDate firstLimitUpDate;

  @Column(name = "last_limit_up_date", nullable = false)
  private LocalDate lastLimitUpDate;

  @Column(name = "base_start_price", precision = 10, scale = 2)
  private BigDecimal baseStartPrice;

  @Column(name = "first_limit_up_open_price", precision = 10, scale = 2)
  private BigDecimal firstLimitUpOpenPrice;

  @Column(name = "first_limit_up_close_price", precision = 10, scale = 2)
  private BigDecimal firstLimitUpClosePrice;

  @Column(name = "last_limit_up_close_price", precision = 10, scale = 2)
  private BigDecimal lastLimitUpClosePrice;

  @Column(name = "trigger_price", precision = 10, scale = 2)
  private BigDecimal triggerPrice;

  @Column(name = "trigger_date")
  private LocalDate triggerDate;

  @Column(name = "status_reason", length = 255)
  private String statusReason;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
