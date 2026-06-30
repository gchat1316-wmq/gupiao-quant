package com.quant.dto.journal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class EquityCurvePoint {
    private Integer tradeIndex;       // 1-based ordinal of closed trade
    private Long tradeId;
    private String exitDate;          // ISO local date
    private BigDecimal cumulativeR;
}