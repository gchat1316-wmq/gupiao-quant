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
@Table(name = "money_watch")
public class MoneyWatch {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "pool_id", nullable = false)
  private Long poolId;

  @Column(name = "user_id", nullable = false)
  private Long userId = 0L;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "stock_name", length = 50)
  private String stockName;

  @Column(name = "status", nullable = false, length = 30)
  private String status = "SCREENING";

  @Column(name = "active_flag", nullable = false)
  private Integer activeFlag = 1;

  @Column(name = "invalid_reason", length = 200)
  private String invalidReason;

  @Column(name = "sector_tag", length = 50)
  private String sectorTag;

  @Column(name = "screen_passed", nullable = false)
  private Integer screenPassed = 0;

  @Column(name = "screen_detail", columnDefinition = "json")
  private String screenDetail;

  @Column(name = "market_regime", length = 20)
  private String marketRegime;

  @Column(name = "index_above_ma20")
  private Integer indexAboveMa20;

  @Column(name = "buy_signal_type", length = 20)
  private String buySignalType;

  @Column(name = "buy_signal_at")
  private LocalDateTime buySignalAt;

  @Column(name = "buy_signal_price", precision = 10, scale = 2)
  private BigDecimal buySignalPrice;

  @Column(name = "signal_expire_at")
  private LocalDateTime signalExpireAt;

  @Column(name = "consecutive_stops", nullable = false)
  private Integer consecutiveStops = 0;

  @Column(name = "paused_until")
  private LocalDate pausedUntil;

  @Column(name = "memo", length = 500)
  private String memo;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
