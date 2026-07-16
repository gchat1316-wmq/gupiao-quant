package com.quant.dto.techai;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PositionFillDTO {
  private Long id;
  private Integer poolId;
  private String stockCode;
  private String action;
  private BigDecimal price;
  private BigDecimal lots;
  private BigDecimal amount;
  private BigDecimal fee;
  private String note;
  private LocalDateTime filledAt;
}
