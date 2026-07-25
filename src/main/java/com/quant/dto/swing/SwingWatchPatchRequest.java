package com.quant.dto.swing;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class SwingWatchPatchRequest {

  private String sectorTag;
  private String thesis;
  private Boolean hardFilterOk;
  private Boolean quietPeriod;
  private String preferredSetup;
  private String status;
  private BigDecimal accountEquity;
  private BigDecimal maxPositionPct;
  private String serverchanSendKey;
  private String note;
}
