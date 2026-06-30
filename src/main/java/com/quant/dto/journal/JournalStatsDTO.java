package com.quant.dto.journal;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class JournalStatsDTO {
    private Integer totalTrades;       // closed count
    private Integer wins;
    private Integer losses;
    private BigDecimal winRate;        // 0.42 = 42%
    private BigDecimal averageR;       // mean of r_multiple
    private BigDecimal averageWinR;    // mean of winning r_multiple
    private BigDecimal averageLossR;   // mean of losing r_multiple (negative)
    private BigDecimal expectedValue;  // win_rate * avg_win + loss_rate * avg_loss
    private BigDecimal maxDrawdown;    // in R units (negative)
    private Long longestWinStreak;
    private Long longestLossStreak;
}