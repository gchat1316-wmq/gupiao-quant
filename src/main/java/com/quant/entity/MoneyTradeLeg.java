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
@Table(name = "money_trade_leg")
public class MoneyTradeLeg {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "position_id", nullable = false)
  private Long positionId;

  @Column(name = "watch_id")
  private Long watchId;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "leg_type", nullable = false, length = 10)
  private String legType;

  @Column(name = "price", nullable = false, precision = 10, scale = 2)
  private BigDecimal price;

  @Column(name = "shares")
  private Integer shares;

  @Column(name = "amount", precision = 14, scale = 2)
  private BigDecimal amount;

  @Column(name = "trade_date", nullable = false)
  private LocalDateTime tradeDate;

  @Column(name = "source", nullable = false, length = 20)
  private String source = "MANUAL";

  @Column(name = "linked_event_id")
  private Long linkedEventId;

  @Column(name = "memo", length = 200)
  private String memo;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
