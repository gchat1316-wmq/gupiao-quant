package com.quant.dto.etfmodel;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class EtfTradeRequest {

  private Long poolId;
  private String stockCode;

  /** BUY | SELL */
  private String direction;

  /** OPEN/ADD/T_TRADE/RECOUP/TP1/TP2/TRAIL_EXIT/SL1/SL2/GUARD_CUT/OTHER */
  private String tradeType;

  private BigDecimal price;
  private Integer shares;

  /** 可空，默认 price * shares */
  private BigDecimal amount;

  /** 可空，默认当前时间 */
  private LocalDateTime tradeTime;

  private String memo;
}
