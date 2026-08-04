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

/** 省心 ETF 信号推送历史 — 页面展示 + 冷却去重依据。stockCode 为 NULL 表示组合级信号。 */
@Getter
@Setter
@Entity
@Table(name = "etf_alert")
public class EtfAlert {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "stock_code", length = 20)
  private String stockCode;

  @Column(name = "signal_type", nullable = false, length = 40)
  private String signalType;

  @Column(name = "title", length = 200)
  private String title;

  @Column(name = "content", columnDefinition = "TEXT")
  private String content;

  @Column(name = "trigger_price", precision = 10, scale = 3)
  private BigDecimal triggerPrice;

  @Column(name = "trigger_at", nullable = false)
  private LocalDateTime triggerAt;

  @Column(name = "pushed")
  private Integer pushed = 0;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
