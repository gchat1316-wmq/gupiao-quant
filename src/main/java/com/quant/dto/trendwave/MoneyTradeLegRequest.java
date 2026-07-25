package com.quant.dto.trendwave;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class MoneyTradeLegRequest {
  private Long positionId;
  private String legType; // SELL | ADD
  private BigDecimal price;
  private Integer shares;
  private BigDecimal sellPct; // optional for SELL: 50 means half
  private LocalDateTime tradeDate;
  private String memo;
  private Long linkedEventId;
}
