package com.quant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class QuarterMetricDTO {
    private String quarter;
    private String reportDate;
    private BigDecimal grossMargin;
    private BigDecimal revenueYoy;
    private BigDecimal deductedNetProfitYoy;
    private BigDecimal deductedNetProfitTtm;
}
