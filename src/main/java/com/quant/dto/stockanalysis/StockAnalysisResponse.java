package com.quant.dto.stockanalysis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockAnalysisResponse {
    private boolean ok;
    private String code;
    private String name;
    private Double currentPrice;
    private String method;
    private String verdict;
    private Integer moatScore;
    private Map<String, Object> chainPosition;
    private Map<String, Object> financialSummary;
    private Map<String, Object> competition;
    private Map<String, Object> threeQuestions;
    private Map<String, Object> nineDimension;
    private List<String> catalysts;
    private List<String> risks;
    private Map<String, Object> rawData;
    private LocalDateTime timestamp;
    private Long elapsedMs;
}
