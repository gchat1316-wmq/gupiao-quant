package com.quant.dto.trendwave;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class MoneyPositionOpenRequest {
  private Long watchId;
  private BigDecimal price;
  private Integer shares;
  private LocalDateTime tradeDate;
  private String memo;
}
