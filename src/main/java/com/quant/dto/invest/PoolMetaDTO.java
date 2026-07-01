package com.quant.dto.invest;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class PoolMetaDTO {
    private String poolType;
    private String displayName;
    private String coverImageUrl;
    /** 估值方法 Markdown 原文，用于编辑弹窗回显。 */
    private String valuationMethodMd;
    private String valuationMethodHtml;
    /** 每周机会点 Markdown 原文，用于编辑弹窗回显。 */
    private String weeklyOpportunityMd;
    private String weeklyOpportunityHtml;
    private Integer displayOrder;
    private LocalDateTime updatedAt;
}