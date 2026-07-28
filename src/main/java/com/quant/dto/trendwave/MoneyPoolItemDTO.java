package com.quant.dto.trendwave;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MoneyPoolItemDTO {
  private Long id;
  private String stockCode;
  private String stockName;
  private String sectorTag;
  private String source;
  private String status;
  private Boolean paperMode;
  private String memo;
  private Long activeWatchId;
  private String watchStatus;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
