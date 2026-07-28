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
@Table(name = "money_event")
public class MoneyEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "watch_id")
  private Long watchId;

  @Column(name = "position_id")
  private Long positionId;

  @Column(name = "pool_id")
  private Long poolId;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "stock_name", length = 50)
  private String stockName;

  @Column(name = "event_type", nullable = false, length = 40)
  private String eventType;

  @Column(name = "severity", nullable = false, length = 10)
  private String severity = "INFO";

  @Column(name = "title", length = 120)
  private String title;

  @Column(name = "content", columnDefinition = "text")
  private String content;

  @Column(name = "trigger_price", precision = 10, scale = 2)
  private BigDecimal triggerPrice;

  @Column(name = "trigger_data", columnDefinition = "json")
  private String triggerData;

  @Column(name = "pushed", nullable = false)
  private Integer pushed = 0;

  @Column(name = "acknowledged", nullable = false)
  private Integer acknowledged = 0;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
