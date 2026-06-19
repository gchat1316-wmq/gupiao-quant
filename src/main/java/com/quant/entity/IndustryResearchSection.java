package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章章节（每个 Tab 一条记录）
 */
@Data
@Entity
@Table(name = "industry_research_section")
public class IndustryResearchSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @Column(name = "section_key", nullable = false, length = 50)
    private String sectionKey;

    @Column(name = "section_title", nullable = false, length = 100)
    private String sectionTitle;

    @Column(name = "section_order", nullable = false)
    private Integer sectionOrder = 0;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType = "mixed";

    @Lob
    @Column(name = "content_json", nullable = false, columnDefinition = "LONGTEXT")
    private String contentJson;

    @Column(name = "source", length = 500)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}