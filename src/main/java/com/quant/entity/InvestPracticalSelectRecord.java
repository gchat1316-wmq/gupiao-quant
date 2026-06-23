package com.quant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 实战选股分析记录。
 * 包含历史快照、PDF 路径、分享 token。
 */
@Getter
@Setter
@Entity
@Table(name = "invest_practical_select_record")
public class InvestPracticalSelectRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 100)
    private String stockName;

    @Column(name = "keyword", length = 100)
    private String keyword;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "SUCCESS";

    /** 综合结论一句话（用于列表展示） */
    @Column(name = "headline", length = 500)
    private String headline;

    /** 估值结论：低估 / 合理 / 高估 */
    @Column(name = "verdict", length = 20)
    private String verdict;

    /** 完整分析结果 JSON */
    @Column(name = "result_json", columnDefinition = "longtext")
    private String resultJson;

    /** PDF 路径（相对 upload-dir） */
    @Column(name = "pdf_path", length = 500)
    private String pdfPath;

    /** 分享 token */
    @Column(name = "share_token", length = 64)
    private String shareToken;

    @Column(name = "is_public", nullable = false)
    private Integer isPublic = 0;

    @Column(name = "elapsed_ms")
    private Long elapsedMs;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}