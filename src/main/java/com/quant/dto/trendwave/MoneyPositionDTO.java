package com.quant.dto.trendwave;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MoneyPositionDTO {
  private Long id;
  private Long watchId;
  private String stockCode;
  private String stockName;
  private String buyType;
  private BigDecimal entryPrice;
  private LocalDateTime entryDate;
  private Integer entryShares;
  private BigDecimal positionPct;
  private BigDecimal peakPrice;
  private String profitTier;
  private BigDecimal stopPrimary;
  private BigDecimal stopSecondary;
  private BigDecimal trailingStop;
  private BigDecimal costStop;
  private Boolean addPositionDone;
  private BigDecimal latestPrice;
  private BigDecimal unrealizedPnlPct;
  private BigDecimal peakDrawdownPct;
  private String status;
  private String closeReason;
  private BigDecimal realizedPnl;
  private BigDecimal realizedPnlPct;
  private LocalDateTime closedAt;
}
