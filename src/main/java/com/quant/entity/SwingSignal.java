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
@Table(name = "swing_signal")
public class SwingSignal {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "watch_id", nullable = false)
  private Long watchId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "setup_id")
  private Long setupId;

  @Column(name = "position_id")
  private Long positionId;

  @Column(name = "signal_type", nullable = false, length = 40)
  private String signalType;

  @Column(name = "level", nullable = false, length = 16)
  private String level;

  @Column(name = "title", nullable = false, length = 200)
  private String title;

  @Column(name = "content", nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(name = "trigger_price", precision = 10, scale = 2)
  private BigDecimal triggerPrice;

  @Column(name = "suggest_action", length = 32)
  private String suggestAction;

  @Column(name = "suggest_shares")
  private Integer suggestShares;

  @Column(name = "suggest_stop", precision = 10, scale = 2)
  private BigDecimal suggestStop;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "dedupe_key", nullable = false, length = 128)
  private String dedupeKey;

  @Column(name = "notified_at")
  private LocalDateTime notifiedAt;

  @Column(name = "acked_at")
  private LocalDateTime ackedAt;

  @Column(name = "executed_at")
  private LocalDateTime executedAt;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
