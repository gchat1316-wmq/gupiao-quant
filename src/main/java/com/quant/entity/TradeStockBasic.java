package com.quant.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "trade_stock_basic")
public class TradeStockBasic {

  @Id
  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "stock_name", length = 50)
  private String stockName;

  @Column(name = "exchange", length = 10)
  private String exchange;

  @Column(name = "list_date")
  private LocalDate listDate;

  @Column(name = "total_shares")
  private Long totalShares;

  @Column(name = "float_shares")
  private Long floatShares;

  @Column(name = "free_float_shares")
  private Long freeFloatShares;

  @Column(name = "is_trading")
  private Integer isTrading;

  @Column(name = "sector_names", columnDefinition = "text")
  private String sectorNames;

  @Column(name = "pe_ttm", precision = 10, scale = 2)
  private BigDecimal peTtm;

  @Column(name = "pb", precision = 10, scale = 2)
  private BigDecimal pb;

  @Column(name = "ps_ttm", precision = 10, scale = 2)
  private BigDecimal psTtm;

  @Column(name = "valuation_level", length = 10)
  private String valuationLevel;

  @Column(name = "valuation_updated_at")
  private LocalDateTime valuationUpdatedAt;

  @Column(name = "data_source", length = 20)
  private String dataSource;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
