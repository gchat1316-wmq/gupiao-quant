package com.quant.dto.trendwave;

import lombok.Data;

@Data
public class MoneyPoolAddRequest {
  private String stockCode;
  private String sectorTag;
  private String source;
  private String memo;
  private Boolean paperMode;
}
