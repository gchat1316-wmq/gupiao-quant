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
    private String valuationLevel;
    private String dataSource;
    private String updatedAt;
}
