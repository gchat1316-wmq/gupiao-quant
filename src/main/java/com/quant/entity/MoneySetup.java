package com.quant.entity;

import java.math.BigDecimal;
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
@Table(name = "money_setup")
public class MoneySetup {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "watch_id", nullable = false)
  private Long watchId;

  @Column(name = "setup_type", nullable = false, length = 20)
  private String setupType;

  @Column(name = "status", nullable = false, length = 20)
  private String status = "ACTIVE";

  @Column(name = "limit_up_dates", columnDefinition = "json")
  private String limitUpDates;

  @Column(name = "limit_up_count")
  private Integer limitUpCount;

  @Column(name = "platform_low", precision = 10, scale = 2)
  private BigDecimal platformLow;

  @Column(name = "platform_open", precision = 10, scale = 2)
  private BigDecimal platformOpen;

  @Column(name = "limit_up_volume")
  private Long limitUpVolume;

  @Column(name = "pullback_low", precision = 10, scale = 2)
  private BigDecimal pullbackLow;

  @Column(name = "platform_high", precision = 10, scale = 2)
  private BigDecimal platformHigh;

  @Column(name = "platform_days")
  private Integer platformDays;

  @Column(name = "breakout_volume_ratio", precision = 6, scale = 2)
  private BigDecimal breakoutVolumeRatio;

  @Column(name = "trigger_price", precision = 10, scale = 2)
  private BigDecimal triggerPrice;

  @Column(name = "trigger_at")
  private LocalDateTime triggerAt;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
