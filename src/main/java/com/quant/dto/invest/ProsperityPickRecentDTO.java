package com.quant.dto.invest;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class ProsperityPickRecentDTO {
    private Long id;
    private String stockCode;
    private String stockName;
    private LocalDate analysisDate;
    private String imageUrl;
    private String summaryOneLiner;
    private List<String> summaryBullets;
    private String valuationVerdict;
    private String technicalVerdict;
    private String capitalVerdict;
    private boolean degraded;
}
