package com.quant.dto.stockanalysis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockAnalysisRecordListDTO {
    private Long id;
    private String stockCode;
    private String stockCodeRaw;
    private String stockName;
    private String method;
    private String status;
    private String verdict;
    private Integer moatScore;
    private BigDecimal currentPrice;
    private Integer elapsedMs;
    private String errorMessage;
    private String summaryOneLiner;
    private Integer sourceCoverage;
    private Boolean hasReport;
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
