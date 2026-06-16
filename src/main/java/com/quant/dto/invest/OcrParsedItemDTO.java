package com.quant.dto.invest;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class OcrParsedItemDTO {
    private String stockName;
    private String stockCode;
    private String poolType;
    private String status;
    private boolean matched;

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
    private BigDecimal currentMarketCap;
    private BigDecimal ytdGainPct;

    private String memo;
}
