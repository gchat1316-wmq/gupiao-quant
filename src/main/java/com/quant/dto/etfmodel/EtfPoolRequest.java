package com.quant.dto.etfmodel;

import lombok.Data;

@Data
public class EtfPoolRequest {

  private String stockCode;
  private String stockName;

  /** BROAD(宽基) | SECTOR(行业/主题) */
  private String category;

  private String memo;
}
