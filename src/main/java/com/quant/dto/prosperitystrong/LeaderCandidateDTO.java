package com.quant.dto.prosperitystrong;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LeaderCandidateDTO {
  private Integer id;
  private LocalDate snapDate;
  private Integer sectorId;
  private String sectorName;
  private String stockCode;
  private String stockName;
  private BigDecimal leaderScore;
  private BigDecimal ytdChange;
  private BigDecimal change5d;
  private BigDecimal turnoverRate;
  private BigDecimal mainInflow5d;
  private Boolean filterPassed;
  private String filterReason;
  private BigDecimal financeScore;
  private Boolean financePassed;
  private String financeReason;
  private BigDecimal mainlineScore;
  private Boolean mainlinePassed;
  private String mainlineReason;
  private String finalStage;
  private BigDecimal revenueYoyMin4q;
  private BigDecimal deductedNetProfitYoyMin4q;
  private BigDecimal grossMarginAvg4q;
  private BigDecimal debtRatioLatest;
  private BigDecimal operatingCashflowSum4q;
  private BigDecimal roeLatest;
}
