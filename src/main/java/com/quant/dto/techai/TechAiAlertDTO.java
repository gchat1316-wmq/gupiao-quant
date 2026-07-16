package com.quant.dto.techai;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TechAiAlertDTO {
  private Long id;
  private String stockCode;
  private String signalType;
  private String title;
  private BigDecimal triggerPrice;
  private LocalDateTime triggerAt;
  private boolean pushed;
  private boolean read;
}
