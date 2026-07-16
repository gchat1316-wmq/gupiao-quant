package com.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "journal")
public class JournalProperties {
  private String refreshCron = "0 30 15 * * MON-FRI";
  private Boolean refreshEnabled = true;
}
