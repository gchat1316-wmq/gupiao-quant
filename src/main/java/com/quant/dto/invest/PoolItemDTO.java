package com.quant.dto.invest;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class PoolItemDTO {
    private Integer id;
    private String stockCode;
    private String stockName;
    private String poolType;
    private String poolTypeLabel;
    private String memo;
    private BigDecimal targetPrice;
    private String status;
    private String statusLabel;
    /** 最新季度营收同比 */
    private BigDecimal latestRevenueYoy;
    /** 最新季度扣非同比 */
    private BigDecimal latestProfitYoy;
    /** 最新季度景气等级 */
    private String latestLevel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
