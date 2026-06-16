package com.quant.dto.invest;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PoolSaveRequest {
    private String keyword;
    private String poolType;
    private String memo;
    /** @deprecated 兼容旧字段 */
    @Deprecated
    private BigDecimal targetPrice;
    private String status;

    private BigDecimal undervaluedPrice;
    private BigDecimal fairPrice;
    private BigDecimal overvaluedPrice;
    private BigDecimal targetBuyPrice;
    private BigDecimal targetSellPrice;
    private BigDecimal revenueForecastY0;
    private BigDecimal revenueForecastY1;
    private BigDecimal revenueForecastY2;

    private BigDecimal revenue2023;
    private BigDecimal revenue2024;
    private BigDecimal revenue2025;
    private BigDecimal q1GrossMargin;
    private BigDecimal q1NetMargin;
    private BigDecimal q1RevenueGrowth;
    private BigDecimal minPs5y;
    private BigDecimal targetMarketCap;
    private BigDecimal currentMarketCap;
    private BigDecimal ytdGainPct;
    private Integer displayOrder;
    private String profitLevel;
    private String valuationRange;
}
