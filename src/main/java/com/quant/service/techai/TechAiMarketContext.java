package com.quant.service.techai;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
