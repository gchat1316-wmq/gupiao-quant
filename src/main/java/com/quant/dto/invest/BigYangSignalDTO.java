package com.quant.dto.invest;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class BigYangSignalDTO {
    private Long id;
    private Integer sourcePoolId;
    private String sourcePoolType;
    private String sourcePoolTypeLabel;
    private String stockCode;
    private String stockName;
    private String signalStatus;
    private Integer limitUpStreak;
    private LocalDate firstLimitUpDate;
    private LocalDate lastLimitUpDate;
    private BigDecimal baseStartPrice;
    private BigDecimal firstLimitUpOpenPrice;
    private BigDecimal firstLimitUpClosePrice;
    private BigDecimal lastLimitUpClosePrice;
    private BigDecimal currentPrice;
    private LocalDate currentPriceDate;
    private BigDecimal distanceToBasePct;
    private BigDecimal triggerPrice;
    private LocalDate triggerDate;
    private String statusReason;
}
