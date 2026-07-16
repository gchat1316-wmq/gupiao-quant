package com.quant.dto.invest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Builder;
import lombok.Data;

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

  /** 护城河评分 */
  private Integer moatScore;

  /** 紫苏叶判定 */
  private String verdict;

  /** 是否有报告详情可查看 */
  private boolean hasReport;

  /** 现价 */
  private BigDecimal currentPrice;
}
