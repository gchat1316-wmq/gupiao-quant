package com.quant.dto.invest;

import java.math.BigDecimal;
import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SopCheckupDTO {

  private boolean matched;
  private String stockCode;
  private String stockName;
  private String message;

  private MetricCheck grossMargin;
  private MetricCheck revenueYoy;
  private MetricCheck profitYoy;

  /** pass / warn / fail */
  private String overallVerdict;

  private String overallSummary;

  @Getter
  @Builder
  public static class MetricCheck {
    private String label;
    private String unit;
    private List<QuarterPoint> series;
    private BigDecimal latest;
    private String verdict;
    private String tip;
  }

  @Getter
  @Builder
  public static class QuarterPoint {
    private String quarter;
    private BigDecimal value;
  }
}
