package com.quant.dto.swing;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SwingSignalDTO {
  private Long id;
  private Long watchId;
  private String stockCode;
  private String stockName;
  private String signalType;
  private String level;
  private String title;
  private String content;
  private BigDecimal triggerPrice;
  private String suggestAction;
  private Integer suggestShares;
  private BigDecimal suggestStop;
  private String status;
  private LocalDateTime notifiedAt;
  private LocalDateTime executedAt;
  private LocalDateTime createdAt;
}
