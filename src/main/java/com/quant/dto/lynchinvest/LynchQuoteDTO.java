package com.quant.dto.lynchinvest;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class LynchQuoteDTO {
    private String stockCode;
    private String stockName;
    private String sectorName;
    private BigDecimal price;
    private BigDecimal peTtm;
    private BigDecimal pb;
    private BigDecimal marketCap;
    private BigDecimal cagrPct;
    private BigDecimal peg;
    private String pegRating;
    private BigDecimal digestYears;
}
