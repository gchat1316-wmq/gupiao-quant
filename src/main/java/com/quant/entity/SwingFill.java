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
@Table(name = "swing_fill")
public class SwingFill {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "position_id", nullable = false)
  private Long positionId;

  @Column(name = "watch_id", nullable = false)
  private Long watchId;

  @Column(name = "side", nullable = false, length = 8)
  private String side;

  @Column(name = "reason", nullable = false, length = 32)
  private String reason;

  @Column(name = "price", nullable = false, precision = 10, scale = 2)
  private BigDecimal price;

  @Column(name = "shares", nullable = false)
  private Integer shares;

  @Column(name = "amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal amount;

  @Column(name = "fill_time", nullable = false)
  private LocalDateTime fillTime;

  @Column(name = "source", nullable = false, length = 16)
  private String source;

  @Column(name = "signal_id")
  private Long signalId;

  @Column(name = "note", length = 255)
  private String note;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
