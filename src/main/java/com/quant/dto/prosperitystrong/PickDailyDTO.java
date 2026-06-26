package com.quant.dto.prosperitystrong;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
    /** 近3季度营收同比最小值(%) — 替代旧的近4季净利率 */
    private BigDecimal revenueYoyMin3q;
    /** 历史字段保留(老接口兼容), 不再展示 */
    private BigDecimal netMarginAvg4q;
    private BigDecimal mainBizRatio;
    private BigDecimal latestPrice;
    private List<ProfitQuarterDTO> profitQuarters;

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

    @Getter
    @Builder
    public static class ProfitQuarterDTO {
        private LocalDate reportDate;
        private String label;
        private BigDecimal netProfit;
        private BigDecimal qoqPct;
        private BigDecimal netMargin;
    }
}
