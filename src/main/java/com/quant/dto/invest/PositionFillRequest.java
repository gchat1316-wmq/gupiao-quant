package com.quant.dto.invest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PositionFillRequest {
  /** open / add / reduce / clear */
  private String action;

  private BigDecimal price;
  private BigDecimal lots;
  private BigDecimal fee;
  private String note;
  private LocalDateTime filledAt;
}
