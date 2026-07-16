package com.quant.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class QueryResultDTO {
  private int requested;
  private int matched;
  private List<String> notFound;
  private List<StockFinancialDTO> stocks;
}
