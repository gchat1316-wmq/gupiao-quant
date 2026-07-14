package com.quant.dto.xiebo;

import com.quant.entity.InvestXieboRecentWatch;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
public class RecentWatchDto {
    private String stockCode;
    private String stockName;
    private String type;
    private BigDecimal currentPrice;
    private BigDecimal priceChange;
    private Boolean hasNote;
    private LocalDateTime createdAt;

    public static RecentWatchDto of(InvestXieboRecentWatch e, BigDecimal cur, BigDecimal prev, Boolean hasNote) {
        if (e == null) return null;
        BigDecimal change = null;
        if (cur != null && prev != null && prev.compareTo(BigDecimal.ZERO) != 0) {
            change = cur.subtract(prev)
                    .divide(prev, 6, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return RecentWatchDto.builder()
                .stockCode(e.getStockCode())
                .stockName(e.getStockName())
                .type(e.getType())
                .currentPrice(cur)
                .priceChange(change)
                .hasNote(hasNote)
                .createdAt(e.getCreatedAt())
                .build();
    }
}
