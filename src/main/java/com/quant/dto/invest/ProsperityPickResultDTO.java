package com.quant.dto.invest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
public class ProsperityPickResultDTO {

  private Long id;
  private String stockCode;
  private String stockName;
  private LocalDate analysisDate;

  /** 公司速览卡的基础数据 */
  private Profile profile;

  /** AI 输出的结构化六维结果（保留为 JsonNode，前端按 schema 渲染） */
  private JsonNode analysis;

  /** 已生成的信息图 URL（懒生成） */
  private String imageUrl;

  /** 是否为退化（mock）数据 */
  private boolean degraded;

  /** 是否命中缓存 */
  private boolean cached;

  // ======== 融合个股分析（紫苏叶 + 九维 + baostock） ========

  /** 紫苏叶产业链定位 */
  private JsonNode chainPosition;

  /** 高景气九维数据 */
  private JsonNode nineDimension;

  /** baostock 财务趋势（近 8 季度，供前端 Chart.js 渲染） */
  private FinancialSummary financialSummary;

  /** 护城河评分 1-10 */
  private Integer moatScore;

  /** 紫苏叶判定 */
  private String verdict;

  /** 催化剂列表 */
  private List<String> catalysts;

  /** 风险列表 */
  private List<String> risks;

  /** 报告详情 HTML */
  private String reportHtml;

  /** 分析耗时 ms */
  private Integer elapsedMs;

  @Data
  @Builder
  public static class Profile {
    private String stockCode;
    private String stockName;
    private String exchange;
    private String board;
    private String industry;
    private String chairman;
    private String mainBusiness;
    private BigDecimal currentPrice;
    private BigDecimal totalMarketCap;
    private BigDecimal peTtm;
    private BigDecimal pb;
    private BigDecimal psTtm;
    private String latestRevenue;
    private String latestNetProfit;
    private String latestReportDate;
  }

  /** 财务趋势摘要（baostock） */
  @Data
  @Builder
  public static class FinancialSummary {
    private List<String> periodLabels;
    private List<Double> roeList;
    private List<Double> grossMarginList;
    private List<Double> netMarginList;
    private List<Double> yoyNetProfitList;
  }
}
