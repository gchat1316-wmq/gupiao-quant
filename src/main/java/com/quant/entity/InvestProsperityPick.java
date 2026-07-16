package com.quant.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "invest_prosperity_pick")
public class InvestProsperityPick {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "stock_name", nullable = false, length = 50)
  private String stockName;

  @Column(name = "analysis_date", nullable = false)
  private LocalDate analysisDate;

  @Column(name = "result_json", columnDefinition = "MEDIUMTEXT")
  private String resultJson;

  @Column(name = "image_url", length = 512)
  private String imageUrl;

  @Column(name = "image_prompt", columnDefinition = "TEXT")
  private String imagePrompt;

  /** 紫苏叶产业链定位 JSON */
  @Column(name = "chain_position", columnDefinition = "TEXT")
  private String chainPosition;

  /** 高景气九维 JSON */
  @Column(name = "nine_dimension", columnDefinition = "TEXT")
  private String nineDimension;

  /** baostock 原始数据包 JSON */
  @Column(name = "baostock_data", columnDefinition = "MEDIUMTEXT")
  private String baostockData;

  /** 护城河评分 1-10 */
  @Column(name = "moat_score")
  private Integer moatScore;

  /** 紫苏叶判定: 盯住/观望/回避 等 */
  @Column(name = "verdict", length = 64)
  private String verdict;

  /** 分析耗时 ms */
  @Column(name = "elapsed_ms")
  private Integer elapsedMs;

  /** 报告详情 HTML (可保存/导出) */
  @Column(name = "report_html", columnDefinition = "MEDIUMTEXT")
  private String reportHtml;

  @Column(name = "degraded", nullable = false)
  private Integer degraded = 0;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
