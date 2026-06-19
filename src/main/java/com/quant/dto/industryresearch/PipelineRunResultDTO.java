package com.quant.dto.industryresearch;

import java.util.List;

/**
 * 流水线运行结果
 */
public record PipelineRunResultDTO(
        Long taskId,
        String status,
        String stage,
        Integer progress,
        Integer totalReports,
        Integer newsCount,
        List<String> stageLog,
        Long articleId
) {}