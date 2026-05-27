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

    private String memo;
}
