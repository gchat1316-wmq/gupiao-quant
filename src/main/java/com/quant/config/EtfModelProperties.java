package com.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** 省心 ETF 交易系统调度配置（业务参数在 etf_model_config 表中，页面可改）。 */
@Data
@ConfigurationProperties(prefix = "etf-model")
public class EtfModelProperties {

  private boolean enabled = true;

  /** 盘中扫描仅在交易时段 (9:30-11:30 / 13:00-15:00) 执行 */
  private boolean requireTradingTime = true;

  /** 同一 (code, signal) 推送冷却分钟数 */
  private int cooldownMinutes = 30;

  /** 盘中每分钟扫描 */
  private String intradayCron = "0 */1 9-15 * * MON-FRI";

  /** 收盘兜底：日K同步 + 阈值复查 + 移动止盈判定 + 净值快照/保命线 */
  private String eodCron = "0 20 15 * * MON-FRI";

  /** 周五盘后回补条件检查（周K连续2周收在5日线上方） */
  private String weeklyCron = "0 40 15 * * FRI";

  /** 日K拉取天数 */
  private int klineDaysBack = 90;
}
