package com.quant.dto.journal;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class JournalTradeCreateRequest {
    /** Required: REAL or PAPER */
    private String mode;
    /** Required */
    private String stockCode;
    private String stockName;
    /** Required */
    private BigDecimal entryPrice;
    /** Defaults to now() if null */
    private LocalDateTime entryDate;
    /** Required: shares (multiple of 100) */
    private Integer entryShares;
    private BigDecimal accountAtEntry;
    /** Optional: 0.01 = 1%. If null, computed from accountAtEntry + initial_risk */
    private BigDecimal riskPercent;
    /** Required */
    private BigDecimal stopPrice;
    private BigDecimal targetPrice;
    private String tags;
    private String setupNotes;
}