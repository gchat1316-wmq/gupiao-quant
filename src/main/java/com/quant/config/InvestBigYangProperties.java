package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@Data
@ConfigurationProperties(prefix = "invest-big-yang")
public class InvestBigYangProperties {

    private boolean enabled = true;
    private boolean startupEnabled = true;
    private int minStreakDays = 1;
    private int maxStreakDays = 2;
    private int candidateLookbackDays = 20;
    private BigDecimal pullbackTolerancePct = BigDecimal.valueOf(2);
    private BigDecimal invalidBreakPct = BigDecimal.valueOf(5);
    private int expireTradingDays = 20;
    private int maxSignals = 200;
    private String candidateCron = "0 35 18 * * MON-FRI";
    private String triggerCron = "0 */5 9-15 * * MON-FRI";
}
