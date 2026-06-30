package com.quant.dto.journal;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class JournalTradeUpdateRequest {
    /** When set, closes the trade. */
    private BigDecimal exitPrice;
    private LocalDateTime exitDate;
    /** stopped_out / target_hit / manual / time_stop / system_stop */
    private String exitReason;
    private BigDecimal stopPrice;
    private BigDecimal targetPrice;
    private String tags;
    private String setupNotes;
    private String reviewNotes;
}