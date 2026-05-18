package com.quant.dto.invest;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class ProsperityQuarterDTO {
    private String quarter;
    private String reportDate;
    private BigDecimal revenueYoy;
    private BigDecimal deductedNetProfitYoy;
    /** 营收同比景气等级: high / medium / weak / low */
    private String revenueLevel;
    /** 扣非同比景气等级 */
    private String profitLevel;
    /** 是否为营收同比转折点（由负转正） */
    private boolean revenueTurnaround;
    /** 是否为扣非同比转折点 */
    private boolean profitTurnaround;
}
