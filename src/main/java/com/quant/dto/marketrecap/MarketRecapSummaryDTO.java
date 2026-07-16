package com.quant.dto.marketrecap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketRecapSummaryDTO {
  private Long id;
  private String market;
  private String tradeDate;
  private String title;
  private String indexesSummary;
  private String advanceDecline;
  private Integer limitUp;
  private Integer limitDown;
  private String sentiment;
  private String summaryExcerpt;
}
