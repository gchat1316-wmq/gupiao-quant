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
@Table(name = "swing_watchlist")
public class SwingWatchlist {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "stock_name", length = 64)
  private String stockName;

  @Column(name = "sector_tag", nullable = false, length = 64)
  private String sectorTag;

  @Column(name = "thesis", length = 500)
  private String thesis;

  @Column(name = "hard_filter_ok", nullable = false)
  private Boolean hardFilterOk = true;

  @Column(name = "quiet_period", nullable = false)
  private Boolean quietPeriod = false;

  @Column(name = "preferred_setup", nullable = false, length = 20)
  private String preferredSetup = "BOTH";

  /** HYBRID = Server酱提醒 + 系统自动记账 */
  @Column(name = "trade_mode", nullable = false, length = 16)
  private String tradeMode = "HYBRID";

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "account_equity", precision = 14, scale = 2)
  private BigDecimal accountEquity;

  @Column(name = "max_position_pct", precision = 5, scale = 2)
  private BigDecimal maxPositionPct;

  @Column(name = "serverchan_send_key", length = 128)
  private String serverchanSendKey;

  @Column(name = "note", length = 500)
  private String note;

  @Column(name = "last_scan_at")
  private LocalDateTime lastScanAt;

  @Column(name = "last_signal_at")
  private LocalDateTime lastSignalAt;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
