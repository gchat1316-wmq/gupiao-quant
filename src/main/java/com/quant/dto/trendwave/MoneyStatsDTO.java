package com.quant.dto.trendwave;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MoneyStatsDTO {
  private long totalTrades;
  private long winTrades;
  private long lossTrades;
  private BigDecimal winRate;
  private BigDecimal avgWinPct;
  private BigDecimal avgLossPct;
  private BigDecimal profitFactor;
  private BigDecimal expectancyPct;
  private BigDecimal totalRealizedPnl;
  private Map<String, GroupStats> byBuyType;
  private Map<String, GroupStats> bySector;
  private List<MoneyPositionDTO> recentClosed;

  @Data
  @Builder
  public static class GroupStats {
    private long count;
    private long wins;
    private BigDecimal winRate;
    private BigDecimal avgPnlPct;
  }
}
