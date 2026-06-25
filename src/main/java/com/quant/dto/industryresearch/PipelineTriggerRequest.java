package com.quant.dto.industryresearch;

/**
 * 触发投研流水线请求
 *
 * @param articleId 可选：指定要更新的文章 ID。如果不传则创建新文章。
 */
public record PipelineTriggerRequest(
        String categoryCode,
        String keyword,
        String taskName,
        Long articleId
) {}
