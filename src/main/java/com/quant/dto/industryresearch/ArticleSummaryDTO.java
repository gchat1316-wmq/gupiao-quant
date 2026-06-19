package com.quant.dto.industryresearch;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 文章摘要（列表用）
 */
public record ArticleSummaryDTO(
        Long id,
        Long categoryId,
        String categoryCode,
        String categoryName,
        String slug,
        String title,
        String subtitle,
        String status,
        Integer version,
        LocalDate updateDate,
        String sourceSummary,
        String tags,
        Integer viewCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}