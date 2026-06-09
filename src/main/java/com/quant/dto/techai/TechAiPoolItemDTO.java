package com.quant.dto.techai;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class TechAiPoolItemDTO {
    private Integer id;
    private String stockCode;
    private String qmtCode;
    private String stockName;
    private String status;
    private String memo;
    private BigDecimal latestPrice;
    private BigDecimal dailyChangePct;
    private BigDecimal turnoverRate;
    private Long volume;
    private LocalDateTime quoteTime;
    private BigDecimal alertMinute1mPct;
    private BigDecimal alertMinute5mPct;
    private BigDecimal alertDailyPct;
    private BigDecimal alertThreeDayPct;
    private BigDecimal alertTurnoverRatioPct;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
