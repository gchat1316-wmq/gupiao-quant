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
    private String valuationMethodHtml;
    private String weeklyOpportunityHtml;
    private Integer displayOrder;
    private LocalDateTime updatedAt;
}