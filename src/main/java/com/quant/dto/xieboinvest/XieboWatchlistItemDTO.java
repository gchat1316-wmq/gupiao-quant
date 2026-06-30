package com.quant.dto.xieboinvest;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class XieboWatchlistItemDTO {
    private String stockCode;
    private String stockName;
    private String sectorName;
    private BigDecimal price;
    private BigDecimal peTtm;
    private BigDecimal pb;
    private BigDecimal marketCap;
    private BigDecimal cagrPct;
    private BigDecimal peg;              // 仅供参考（PEG法已废弃）
    private String pegRating;           // 仅供参考
    private BigDecimal digestYears;      // 仅供参考
    // 10xPS 统一估值
    private String valuationVerdict;    // 低估 / 合理 / 泡沫 / —
    private String valuationCommentary; // 估值说明
    // 估值偏离（基于 Y1/Y2 合理市值的百分比偏离）
    private BigDecimal valuationDeviationPct;  // 有符号百分比，正=高估，负=低估
    private String valuationDeviationRef;     // 参考年：Y1 / Y2
    private String valuationDeviationLabel;   // 预格式化标签，如"低于 Y1 估值 15.0%"
}
