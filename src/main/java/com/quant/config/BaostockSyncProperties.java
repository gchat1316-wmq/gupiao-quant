package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "baostock-sync")
public class BaostockSyncProperties {

    private boolean enabled = true;
    private boolean startupEnabled = true;
    private int startupDaysBack = 45;
    private int dailyDaysBack = 7;
    private String dailyCron = "0 20 18 * * MON-FRI";
    private String pythonCommand = "python3";
    private int timeoutSeconds = 1800;
}
