package com.quant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "invest_xiebo_analysis_record")
public class InvestXieboAnalysisRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 64)
    private String stockName;

    @Column(name = "analysis_date", nullable = false)
    private LocalDate analysisDate;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "peg_value", precision = 10, scale = 2)
    private BigDecimal pegValue;

    @Column(name = "peg_rating", length = 32)
    private String pegRating;

    @Column(name = "conclusion", length = 500)
    private String conclusion;

    @Column(name = "report_markdown", columnDefinition = "LONGTEXT")
    private String reportMarkdown;

    @Column(name = "raw_snapshot_json", columnDefinition = "LONGTEXT")
    private String rawSnapshotJson;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
