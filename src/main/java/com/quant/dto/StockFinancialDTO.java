package com.quant.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class StockFinancialDTO {
    private String stockCode;
    private String stockName;
    private List<QuarterMetricDTO> quarters;
}
