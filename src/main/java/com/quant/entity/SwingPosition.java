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
@Table(name = "swing_position")
public class SwingPosition {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "watch_id", nullable = false)
  private Long watchId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "setup_id")
  private Long setupId;

  @Column(name = "setup_type", nullable = false, length = 20)
  private String setupType;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "entry_time", nullable = false)
  private LocalDateTime entryTime;

  @Column(name = "avg_cost", nullable = false, precision = 10, scale = 2)
  private BigDecimal avgCost;

  @Column(name = "shares", nullable = false)
  private Integer shares = 0;

  @Column(name = "initial_shares", nullable = false)
  private Integer initialShares;

  @Column(name = "peak_price", nullable = false, precision = 10, scale = 2)
  private BigDecimal peakPrice;

  @Column(name = "realized_pnl", precision = 14, scale = 2)
  private BigDecimal realizedPnl = BigDecimal.ZERO;

  @Column(name = "unrealized_pnl", precision = 14, scale = 2)
  private BigDecimal unrealizedPnl;

  @Column(name = "stop_price", nullable = false, precision = 10, scale = 2)
  private BigDecimal stopPrice;

  @Column(name = "hard_stop_price", nullable = false, precision = 10, scale = 2)
  private BigDecimal hardStopPrice;

  @Column(name = "soft_stop_pct", precision = 6, scale = 4)
  private BigDecimal softStopPct;

  @Column(name = "trail_drawdown_pct", precision = 6, scale = 4)
  private BigDecimal trailDrawdownPct;

  @Column(name = "locked_profit_pct", precision = 6, scale = 4)
  private BigDecimal lockedProfitPct;

  @Column(name = "profit_tier", nullable = false, length = 16)
  private String profitTier = "T0";

  @Column(name = "add_count", nullable = false)
  private Integer addCount = 0;

  @Column(name = "partial_exits", nullable = false)
  private Integer partialExits = 0;

  @Column(name = "ma20_break_days", nullable = false)
  private Integer ma20BreakDays = 0;

  @Column(name = "exit_time")
  private LocalDateTime exitTime;

  @Column(name = "exit_reason", length = 64)
  private String exitReason;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
