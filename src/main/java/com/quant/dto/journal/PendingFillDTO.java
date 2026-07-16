package com.quant.dto.journal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PendingFillDTO {
  private Long fillId;
  private String poolType; // invest_stock_pool / tech_ai_pool / potential_pool
  private String stockCode;
  private String stockName;
  private String action; // open / add / reduce / clear
  private BigDecimal price;
  private BigDecimal lots;
  private LocalDateTime filledAt;
  private String note;
}
