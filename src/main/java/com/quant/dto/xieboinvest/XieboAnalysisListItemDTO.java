package com.quant.dto.xieboinvest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class XieboAnalysisListItemDTO {
  private Long id;
  private String stockCode;
  private String stockName;
  private LocalDate analysisDate;
  private String status;
  private BigDecimal pegValue;
  private String pegRating;
  private String conclusion;
  private LocalDateTime createdAt;
}
