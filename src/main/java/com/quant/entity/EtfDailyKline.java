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

/** ETF 日 K（腾讯 fqkline 前复权）。ETF 价格 3 位小数，独立于 trade_stock_daily。 */
@Getter
@Setter
@Entity
@Table(name = "etf_daily_kline")
public class EtfDailyKline {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "trade_date", nullable = false)
  private LocalDate tradeDate;

  @Column(name = "open_price", precision = 10, scale = 3)
  private BigDecimal openPrice;

  @Column(name = "high_price", precision = 10, scale = 3)
  private BigDecimal highPrice;

  @Column(name = "low_price", precision = 10, scale = 3)
  private BigDecimal lowPrice;

  @Column(name = "close_price", precision = 10, scale = 3)
  private BigDecimal closePrice;

  @Column(name = "volume")
  private Long volume;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
