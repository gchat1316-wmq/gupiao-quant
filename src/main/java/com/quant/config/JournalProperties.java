package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "journal")
public class JournalProperties {
    private String refreshCron = "0 30 15 * * MON-FRI";
    private Boolean refreshEnabled = true;
}
