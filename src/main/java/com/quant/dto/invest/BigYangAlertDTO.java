package com.quant.dto.invest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BigYangAlertDTO {
  private Long id;
  private String stockCode;
  private String stockName;
  private String title;
  private String content;
  private BigDecimal triggerPrice;
  private LocalDateTime triggerAt;
  private boolean read;
}
