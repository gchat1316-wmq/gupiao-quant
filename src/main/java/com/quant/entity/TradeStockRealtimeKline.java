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
@Table(name = "trade_stock_realtime_kline")
public class TradeStockRealtimeKline {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "period", nullable = false, length = 10)
  private String period;

  @Column(name = "kline_time", nullable = false)
  private LocalDateTime klineTime;

  @Column(name = "open_price", precision = 12, scale = 4)
  private BigDecimal openPrice;

  @Column(name = "high_price", precision = 12, scale = 4)
  private BigDecimal highPrice;

  @Column(name = "low_price", precision = 12, scale = 4)
  private BigDecimal lowPrice;

  @Column(name = "close_price", precision = 12, scale = 4)
  private BigDecimal closePrice;

  @Column(name = "volume")
  private Long volume;

  @Column(name = "amount", precision = 20, scale = 2)
  private BigDecimal amount;

  @Column(name = "pre_close", precision = 12, scale = 4)
  private BigDecimal preClose;

  @Column(name = "turnover_rate", precision = 10, scale = 4)
  private BigDecimal turnoverRate;
}
