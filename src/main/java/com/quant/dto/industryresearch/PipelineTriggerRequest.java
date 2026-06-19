package com.quant.dto.industryresearch;

/**
 * 触发投研流水线请求
 */
public record PipelineTriggerRequest(
        String categoryCode,
        String keyword,
        String taskName
) {}