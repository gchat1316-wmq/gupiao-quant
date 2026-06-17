package com.quant.dto.lynchinvest;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class LynchAnalysisDetailDTO {
    private Long id;
    private String stockCode;
    private String stockName;
    private LocalDate analysisDate;
    private String status;
    private BigDecimal pegValue;
    private String pegRating;
    private String conclusion;
    private String reportMarkdown;
    private String rawSnapshotJson;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
