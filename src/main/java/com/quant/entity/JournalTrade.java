package com.quant.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 交易日志条目（模拟/真实账户入场/出场记录）。
 *
 * @see SchemaInitializer#ensureJournalTables()
 */
@Getter
@Setter
@Entity
@Table(name = "journal_trade")
public class JournalTrade {

  public enum Mode {
    REAL,
    PAPER
  }

  public enum ExitReason {
    stopped_out,
    target_hit,
    manual,
    time_stop,
    system_stop
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "mode", nullable = false, length = 10)
  private Mode mode;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "stock_name", length = 50)
  private String stockName;

  @Column(name = "entry_price", nullable = false, precision = 10, scale = 2)
  private BigDecimal entryPrice;

  @Column(name = "entry_date", nullable = false)
  private LocalDateTime entryDate;

  @Column(name = "entry_shares", nullable = false)
  private Integer entryShares;

  @Column(name = "account_at_entry", precision = 14, scale = 2)
  private BigDecimal accountAtEntry;

  @Column(name = "risk_percent", precision = 5, scale = 4)
  private BigDecimal riskPercent;

  @Column(name = "stop_price", nullable = false, precision = 10, scale = 2)
  private BigDecimal stopPrice;

  @Column(name = "target_price", precision = 10, scale = 2)
  private BigDecimal targetPrice;

  @Column(name = "exit_price", precision = 10, scale = 2)
  private BigDecimal exitPrice;

  @Column(name = "exit_date")
  private LocalDateTime exitDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "exit_reason", length = 30)
  private ExitReason exitReason;

  @Column(name = "initial_risk", nullable = false, precision = 10, scale = 2)
  private BigDecimal initialRisk;

  @Column(name = "pnl_amount", precision = 14, scale = 2)
  private BigDecimal pnlAmount;

  @Column(name = "r_multiple", precision = 8, scale = 4)
  private BigDecimal rMultiple;

  @Column(name = "is_open")
  private Integer isOpen = 1;

  @Column(name = "tags", length = 200)
  private String tags;

  @Column(name = "setup_notes", columnDefinition = "TEXT")
  private String setupNotes;

  @Column(name = "review_notes", columnDefinition = "TEXT")
  private String reviewNotes;

  @Column(name = "source", length = 20)
  private String source;

  @Column(name = "source_ref_id")
  private Long sourceRefId;

  @Column(name = "created_by", length = 50)
  private String createdBy;

  @Column(name = "is_deleted")
  private Integer isDeleted = 0;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
