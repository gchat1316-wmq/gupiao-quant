package com.quant.dto.invest;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BigYangSummaryDTO {
    private long unreadAlertCount;
    private long watchingCount;
    private long triggeredCount;
    private long expiredCount;
    private long todayNewWatchingCount;
    private long todayTriggeredCount;
}
