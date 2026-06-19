package com.quant.dto.industryresearch;

import java.time.LocalDateTime;

/**
 * 投研任务 DTO
 */
public record TaskDTO(
        Long id,
        Long categoryId,
        String categoryCode,
        String categoryName,
        Long articleId,
        String taskName,
        String keyword,
        String status,
        String stage,
        Integer progress,
        Integer totalReports,
        Integer newsCount,
        String errorMessage,
        String log,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
) {}