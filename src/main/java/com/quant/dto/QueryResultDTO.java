package com.quant.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class QueryResultDTO {
    private int requested;
    private int matched;
    private List<String> notFound;
    private List<StockFinancialDTO> stocks;
}
