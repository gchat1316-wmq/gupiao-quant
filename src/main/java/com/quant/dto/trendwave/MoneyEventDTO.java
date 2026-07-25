package com.quant.dto.trendwave;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MoneyEventDTO {
  private Long id;
  private Long watchId;
  private Long positionId;
  private String stockCode;
  private String stockName;
  private String eventType;
  private String severity;
  private String title;
  private String content;
  private BigDecimal triggerPrice;
  private Boolean pushed;
  private Boolean acknowledged;
  private LocalDateTime createdAt;
}
