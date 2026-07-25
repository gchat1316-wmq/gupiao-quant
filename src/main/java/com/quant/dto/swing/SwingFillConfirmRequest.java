package com.quant.dto.swing;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SwingFillConfirmRequest {

  @NotNull private BigDecimal price;

  @NotNull private Integer shares;

  /** BUY | SELL，默认按信号建议 */
  private String side;

  private String note;
}
