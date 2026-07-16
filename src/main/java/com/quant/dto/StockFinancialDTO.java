package com.quant.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StockFinancialDTO {
  private String stockCode;
  private String stockName;
  private StockBasicInfoDTO basicInfo;
  private List<QuarterMetricDTO> quarters;
}
