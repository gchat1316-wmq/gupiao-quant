package com.quant.dto.invest;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BigYangRunResultDTO {
  private String reason;
  private int createdWatchingCount;
  private int triggeredCount;
  private int expiredCount;
  private LocalDateTime ranAt;
  private String message;
}
