package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 投研任务（A-Stock-Data + Kimi CLI + News Radar 流水线）
 */
@Data
@Entity
@Table(name = "industry_research_task")
public class IndustryResearchTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "article_id")
    private Long articleId;

    @Column(name = "task_name", nullable = false, length = 200)
    private String taskName;

    @Column(name = "keyword", length = 500)
    private String keyword;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "pending";

    @Column(name = "stage", nullable = false, length = 30)
    private String stage = "init";

    @Column(name = "progress", nullable = false)
    private Integer progress = 0;

    @Column(name = "total_reports")
    private Integer totalReports;

    @Column(name = "news_count")
    private Integer newsCount;

    @Lob
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Lob
    @Column(name = "log", columnDefinition = "TEXT")
    private String log;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}