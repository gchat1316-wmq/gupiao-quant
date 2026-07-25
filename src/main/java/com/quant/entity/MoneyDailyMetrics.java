package com.quant.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "money_daily_metrics")
@IdClass(MoneyDailyMetrics.Pk.class)
public class MoneyDailyMetrics {

  @Id
  @Column(name = "stock_code", length = 20)
  private String stockCode;

  @Id
  @Column(name = "trade_date")
  private LocalDate tradeDate;

  @Column(name = "ma5", precision = 10, scale = 2)
  private BigDecimal ma5;

  @Column(name = "ma10", precision = 10, scale = 2)
  private BigDecimal ma10;

  @Column(name = "ma20", precision = 10, scale = 2)
  private BigDecimal ma20;

  @Column(name = "ma60", precision = 10, scale = 2)
  private BigDecimal ma60;

  @Column(name = "ma20_slope", precision = 10, scale = 6)
  private BigDecimal ma20Slope;

  @Column(name = "vol_ma5")
  private Long volMa5;

  @Column(name = "vol_ma20")
  private Long volMa20;

  @Column(name = "vol_ratio", precision = 8, scale = 4)
  private BigDecimal volRatio;

  @Column(name = "is_limit_up", nullable = false)
  private Integer isLimitUp = 0;

  @Column(name = "close_price", precision = 10, scale = 2)
  private BigDecimal closePrice;

  @Column(name = "volume")
  private Long volume;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @EqualsAndHashCode
  public static class Pk implements java.io.Serializable {
    private String stockCode;
    private LocalDate tradeDate;
  }
}
