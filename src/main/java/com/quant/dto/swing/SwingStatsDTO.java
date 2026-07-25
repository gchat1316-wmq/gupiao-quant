package com.quant.dto.swing;

import java.math.BigDecimal;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SwingStatsDTO {
  private int totalClosed;
  private int wins;
  private int losses;
  private BigDecimal winRate;
  private BigDecimal totalPnl;
  private BigDecimal avgWin;
  private BigDecimal avgLoss;
  private BigDecimal profitFactor;
  private Map<String, Integer> exitReasonCounts;
  private Map<String, Integer> setupTypeCounts;
  private int openPositions;
  private int watchingCount;
}
