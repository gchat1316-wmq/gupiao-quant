package com.quant.dto.industryresearch;

import java.util.List;

/**
 * 文章详情（含 11 个 Tab 章节）
 */
public record ArticleDetailDTO(
        ArticleSummaryDTO summary,
        List<SectionDTO> sections
) {}