package com.quant.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class StockBasicInfoDTO {
    private String stockCode;
    private String stockName;
    private String exchange;
    private String board;
    private String industry;
    private int extraIndustryCount;
    private String listDate;
    private int listYears;
    private BigDecimal peTtm;
    private BigDecimal pb;
    private BigDecimal psTtm;
    private BigDecimal currentMarketCapYi;
    private BigDecimal latestNetMargin;
    private BigDecimal forecastRevenueY1Yi;
    private BigDecimal forecastRevenueY2Yi;
    private BigDecimal forecastRevenueY3Yi;
    private Boolean tenPsCandidate;
    private BigDecimal tenPsFairMarketCapYi;
    private BigDecimal tenPsCurrentToY1;
    private String tenPsValuationVerdict;
    private String tenPsValuationDetail;
    private String valuationLevel;
    private String dataSource;
    private String updatedAt;
}
