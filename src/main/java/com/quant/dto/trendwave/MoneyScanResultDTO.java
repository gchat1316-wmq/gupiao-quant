package com.quant.dto.trendwave;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MoneyScanResultDTO {
  private String mode;
  private int scanned;
  private int signals;
  private int pushed;
  private String message;
  private LocalDateTime ranAt;
}
