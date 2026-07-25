package com.quant.dto.swing;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SwingWatchRequest {

  @NotBlank private String stockCode;

  @NotBlank private String sectorTag;

  private String thesis;

  private Boolean hardFilterOk = true;

  private Boolean quietPeriod = false;

  /** PULLBACK | BREAKOUT | BOTH */
  private String preferredSetup = "BOTH";

  private BigDecimal accountEquity;

  private BigDecimal maxPositionPct;

  private String serverchanSendKey;

  private String note;
}
