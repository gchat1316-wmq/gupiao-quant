package com.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@ConfigurationProperties(prefix = "xiebo-recent-alert")
@Data
public class XieboRecentAlertProperties {

  /** 总开关 */
  private boolean enabled = true;

  /** cron:每 5 分钟,交易日 9-15 时 */
  private String cron = "0 */5 9-15 * * MON-FRI";
}
