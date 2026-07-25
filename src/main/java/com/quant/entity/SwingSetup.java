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
@Table(name = "swing_setup")
public class SwingSetup {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "watch_id", nullable = false)
  private Long watchId;

  @Column(name = "setup_type", nullable = false, length = 20)
  private String setupType;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "ma5", precision = 12, scale = 4)
  private BigDecimal ma5;

  @Column(name = "ma10", precision = 12, scale = 4)
  private BigDecimal ma10;

  @Column(name = "ma20", precision = 12, scale = 4)
  private BigDecimal ma20;

  @Column(name = "ma60", precision = 12, scale = 4)
  private BigDecimal ma60;

  @Column(name = "ma20_slope", precision = 12, scale = 6)
  private BigDecimal ma20Slope;

  @Column(name = "vol_ma20")
  private Long volMa20;

  @Column(name = "vol_ma60")
  private Long volMa60;

  @Column(name = "vol_ratio", precision = 8, scale = 4)
  private BigDecimal volRatio;

  @Column(name = "limit_up_date")
  private LocalDate limitUpDate;

  @Column(name = "limit_up_open", precision = 10, scale = 2)
  private BigDecimal limitUpOpen;

  @Column(name = "limit_up_low", precision = 10, scale = 2)
  private BigDecimal limitUpLow;

  @Column(name = "limit_up_close", precision = 10, scale = 2)
  private BigDecimal limitUpClose;

  @Column(name = "limit_up_volume")
  private Long limitUpVolume;

  @Column(name = "pullback_zone_high", precision = 10, scale = 2)
  private BigDecimal pullbackZoneHigh;

  @Column(name = "pullback_zone_low", precision = 10, scale = 2)
  private BigDecimal pullbackZoneLow;

  @Column(name = "platform_high", precision = 10, scale = 2)
  private BigDecimal platformHigh;

  @Column(name = "platform_start")
  private LocalDate platformStart;

  @Column(name = "platform_end")
  private LocalDate platformEnd;

  @Column(name = "platform_days")
  private Integer platformDays;

  @Column(name = "expire_date")
  private LocalDate expireDate;

  @Column(name = "invalid_reason", length = 255)
  private String invalidReason;

  @Column(name = "detected_at", nullable = false)
  private LocalDateTime detectedAt;

  @Column(name = "triggered_at")
  private LocalDateTime triggeredAt;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
