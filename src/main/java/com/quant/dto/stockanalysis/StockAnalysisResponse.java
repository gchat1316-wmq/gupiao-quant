package com.quant.dto.stockanalysis;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
  private Map<String, Object> analysis;
  private Map<String, Object> sourceMetadata;
  private List<Map<String, Object>> dbFinancials;
  private Map<String, Object> forecastSummary;
  private Map<String, Object> externalExpectation;
  private List<String> catalysts;
  private List<String> risks;
  private String reportHtml;
  private Map<String, Object> rawData;
  private LocalDateTime timestamp;
  private Long elapsedMs;

  /** Wind 研报上下文（一致预期 + 研报片段, 24h 缓存）。失败时 available=false, 不影响主报告 */
  private com.quant.dto.stockanalysis.WindResearchContext windResearch;
}
