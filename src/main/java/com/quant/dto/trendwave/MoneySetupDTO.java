package com.quant.dto.trendwave;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MoneySetupDTO {
  private Long id;
  private String setupType;
  private String status;
  private Integer limitUpCount;
  private BigDecimal platformLow;
  private BigDecimal platformOpen;
  private Long limitUpVolume;
  private BigDecimal pullbackLow;
  private BigDecimal platformHigh;
  private Integer platformDays;
  private BigDecimal breakoutVolumeRatio;
  private BigDecimal triggerPrice;
  private LocalDateTime triggerAt;
}
