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

/** 省心 ETF 每笔交易流水（手动录入；source=QMT 预留光大证券导入）。 */
@Getter
@Setter
@Entity
@Table(name = "etf_trade")
public class EtfTrade {

  public static final String DIR_BUY = "BUY";
  public static final String DIR_SELL = "SELL";

  public static final String TYPE_OPEN = "OPEN";
  public static final String TYPE_ADD = "ADD";
  public static final String TYPE_T_TRADE = "T_TRADE";
  public static final String TYPE_RECOUP = "RECOUP";
  public static final String TYPE_TP1 = "TP1";
  public static final String TYPE_TP2 = "TP2";
  public static final String TYPE_TRAIL_EXIT = "TRAIL_EXIT";
  public static final String TYPE_SL1 = "SL1";
  public static final String TYPE_SL2 = "SL2";
  public static final String TYPE_GUARD_CUT = "GUARD_CUT";
  public static final String TYPE_OTHER = "OTHER";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "pool_id", nullable = false)
  private Long poolId;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "direction", nullable = false, length = 4)
  private String direction;

  @Column(name = "trade_type", nullable = false, length = 20)
  private String tradeType = TYPE_OTHER;

  @Column(name = "price", nullable = false, precision = 10, scale = 3)
  private BigDecimal price;

  @Column(name = "shares", nullable = false)
  private Integer shares;

  @Column(name = "amount", nullable = false, precision = 14, scale = 2)
  private BigDecimal amount;

  @Column(name = "trade_time", nullable = false)
  private LocalDateTime tradeTime;

  @Column(name = "source", nullable = false, length = 10)
  private String source = "MANUAL";

  @Column(name = "memo", length = 200)
  private String memo;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  public boolean isBuy() {
    return DIR_BUY.equals(direction);
  }
}
