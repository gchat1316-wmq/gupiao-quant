package com.quant.dto.swing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SwingWatchDTO {
  private Long id;
  private String stockCode;
  private String stockName;
  private String sectorTag;
  private String thesis;
  private Boolean hardFilterOk;
  private Boolean quietPeriod;
  private String preferredSetup;
  private String tradeMode;
  private String status;
  private BigDecimal accountEquity;
  private BigDecimal maxPositionPct;
  private BigDecimal latestPrice;
  private BigDecimal ma20;
  private Boolean preconditionsOk;
  private String activeSetupType;
  private BigDecimal pullbackZoneHigh;
  private BigDecimal pullbackZoneLow;
  private BigDecimal platformHigh;
  private BigDecimal avgCost;
  private Integer shares;
  private BigDecimal peakPrice;
  private BigDecimal stopPrice;
  private BigDecimal unrealizedPnl;
  private BigDecimal unrealizedPnlPct;
  private String profitTier;
  private LocalDateTime lastScanAt;
  private LocalDateTime lastSignalAt;
  private LocalDateTime createdAt;
  private List<SwingSignalDTO> recentSignals;
}
