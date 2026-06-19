package com.quant.dto.industryresearch;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 文章章节（每个 Tab）
 */
public record SectionDTO(
        Long id,
        String sectionKey,
        String sectionTitle,
        Integer sectionOrder,
        String contentType,
        JsonNode content,
        String source
) {}