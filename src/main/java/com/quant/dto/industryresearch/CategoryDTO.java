package com.quant.dto.industryresearch;

import java.time.LocalDateTime;

/**
 * 产业目录 DTO（左侧菜单用）
 */
public record CategoryDTO(
        Long id,
        String code,
        String name,
        String icon,
        Integer sortOrder,
        Integer articleCount,
        String description
) {}