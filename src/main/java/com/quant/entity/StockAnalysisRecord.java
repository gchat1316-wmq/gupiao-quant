package com.quant.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stock_analysis_record")
public class StockAnalysisRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "stock_code", nullable = false, length = 16)
  private String stockCode;

  @Column(name = "stock_code_raw", nullable = false, length = 16)
  private String stockCodeRaw;

  @Column(name = "stock_name", length = 64)
  private String stockName;

  @Column(nullable = false, length = 32)
  private String method = "full";

  @Column(nullable = false)
  private Integer years = 2;

  @Column(nullable = false)
  private Integer lite = 1;

  @Column(name = "quote_days", nullable = false)
  private Integer quoteDays = 60;

  /** PENDING / RUNNING / SUCCESS / FAILED */
  @Column(nullable = false, length = 16)
  private String status = "PENDING";

  @Column(name = "error_message", length = 1024)
  private String errorMessage;

  @Column(name = "current_price", precision = 18, scale = 4)
  private BigDecimal currentPrice;

  @Column(length = 64)
  private String verdict;

  @Column(name = "moat_score")
  private Integer moatScore;

  @Column(name = "elapsed_ms")
  private Integer elapsedMs;

  @Column(name = "result_json", columnDefinition = "LONGTEXT")
  private String resultJson;

  @Column(name = "source_payload_json", columnDefinition = "LONGTEXT")
  private String sourcePayloadJson;

  @Column(name = "report_html", columnDefinition = "LONGTEXT")
  private String reportHtml;

  @Column(name = "submitted_at", insertable = false, updatable = false)
  private LocalDateTime submittedAt;

  @Column(name = "started_at")
  private LocalDateTime startedAt;

  @Column(name = "finished_at")
  private LocalDateTime finishedAt;

  /** PDF 文件路径 (相对 uploads/ 目录) */
  @Column(name = "pdf_path", length = 255)
  private String pdfPath;
}
