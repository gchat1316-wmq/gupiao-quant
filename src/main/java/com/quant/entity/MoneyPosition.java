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
@Table(name = "money_position")
public class MoneyPosition {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "watch_id", nullable = false)
  private Long watchId;

  @Column(name = "pool_id", nullable = false)
  private Long poolId;

  @Column(name = "user_id", nullable = false)
  private Long userId = 0L;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "stock_name", length = 50)
  private String stockName;

  @Column(name = "buy_type", nullable = false, length = 20)
  private String buyType;

  @Column(name = "entry_price", nullable = false, precision = 10, scale = 2)
  private BigDecimal entryPrice;

  @Column(name = "entry_date", nullable = false)
  private LocalDateTime entryDate;

  @Column(name = "entry_shares")
  private Integer entryShares;

  @Column(name = "position_pct", nullable = false, precision = 5, scale = 2)
  private BigDecimal positionPct = BigDecimal.valueOf(100);

  @Column(name = "peak_price", precision = 10, scale = 2)
  private BigDecimal peakPrice;

  @Column(name = "profit_tier", nullable = false, length = 10)
  private String profitTier = "T0";

  @Column(name = "stop_primary", precision = 10, scale = 2)
  private BigDecimal stopPrimary;

  @Column(name = "stop_secondary", precision = 10, scale = 2)
  private BigDecimal stopSecondary;

  @Column(name = "trailing_stop", precision = 10, scale = 2)
  private BigDecimal trailingStop;

  @Column(name = "cost_stop", precision = 10, scale = 2)
  private BigDecimal costStop;

  @Column(name = "add_position_done", nullable = false)
  private Integer addPositionDone = 0;

  @Column(name = "add_entry_price", precision = 10, scale = 2)
  private BigDecimal addEntryPrice;

  @Column(name = "add_shares")
  private Integer addShares;

  @Column(name = "ma_snapshot", columnDefinition = "json")
  private String maSnapshot;

  @Column(name = "below_ma20_days", nullable = false)
  private Integer belowMa20Days = 0;

  @Column(name = "status", nullable = false, length = 20)
  private String status = "HOLDING";

  @Column(name = "closed_at")
  private LocalDateTime closedAt;

  @Column(name = "close_reason", length = 50)
  private String closeReason;

  @Column(name = "realized_pnl", precision = 14, scale = 2)
  private BigDecimal realizedPnl;

  @Column(name = "realized_pnl_pct", precision = 8, scale = 2)
  private BigDecimal realizedPnlPct;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
