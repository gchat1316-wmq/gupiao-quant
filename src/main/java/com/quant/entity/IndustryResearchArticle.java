package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 产业投研文章主表
 */
@Data
@Entity
@Table(name = "industry_research_article")
public class IndustryResearchArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "slug", nullable = false, unique = true, length = 80)
    private String slug;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "subtitle", length = 500)
    private String subtitle;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "draft";

    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "update_date")
    private LocalDate updateDate;

    @Column(name = "source_summary", length = 500)
    private String sourceSummary;

    @Column(name = "cover_image", length = 500)
    private String coverImage;

    @Column(name = "tags", length = 500)
    private String tags;

    @Column(name = "view_count", nullable = false)
    private Integer viewCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}