package com.quant.dto.prosperitystrong;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class PickDailyDTO {
    private Integer id;
    private LocalDate snapDate;
    private String stockCode;
    private String stockName;
    private String sectorName;
    private BigDecimal financeScore;
    private BigDecimal mainlineScore;
    private BigDecimal combinedScore;
    private BigDecimal netMarginAvg4q;
    private BigDecimal mainBizRatio;
    private BigDecimal latestPrice;

    /** 仓位决策（核心字段） */
    private BigDecimal priceLow;
    private BigDecimal priceMid;
    private BigDecimal priceHigh;
    private BigDecimal buyLeftPrice;
    private BigDecimal buyRightPrice;
    private BigDecimal sellTarget1;
    private BigDecimal sellTarget2;
    private BigDecimal stopLossPrice;
    private BigDecimal corePositionPct;
    private BigDecimal tacticalPositionPct;
    private String actionSignal;

    /** AI 报告(可选,深度页面才返回) */
    private JsonNode aiReport;
    private Boolean degraded;

    private LocalDateTime createdAt;
}
