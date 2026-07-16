package com.quant.service.techai;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TechAiMarketContext {
  private String stockCode;
  private String stockName;
  private LocalDateTime quoteTime;
  private BigDecimal latestPrice;
  private BigDecimal prevClosePrice;
  private BigDecimal openPrice;
  private BigDecimal minute1OpenPrice;
  private BigDecimal minute5OpenPrice;
  private BigDecimal turnoverRate;
  private BigDecimal avgTurnoverRate5d;
  private BigDecimal closePrice3TradingDaysAgo;
  private Long volume;
}
