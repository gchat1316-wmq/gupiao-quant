package com.quant.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

  private ServerChan serverchan = new ServerChan();
  private PriceMonitor priceMonitor = new PriceMonitor();
  private QuoteMonitor quoteMonitor = new QuoteMonitor();
  private WishPool wishPool = new WishPool();

  /** 统一监控 (2026-06-30) — 融合价格/涨跌幅/ATR/止盈止损 的统一调度配置 */
  private Monitor monitor = new Monitor();

  @Data
  public static class ServerChan {
    private boolean enabled = false;
    private String sendKey;
    private String baseUrl = "https://sctapi.ftqq.com";
    private int timeoutSeconds = 10;
  }

  @Data
  public static class PriceMonitor {
    private boolean enabled = false;
    private boolean requireTradingTime = true;
    private int cooldownMinutes = 30;
  }

  @Data
  public static class QuoteMonitor {
    private boolean enabled = false;
    private String poolType = "tech_ai";
    private boolean requireTradingTime = true;
    private int cooldownMinutes = 10;
    private boolean dailyDedupe = true;
    private String cron = "0 */1 9-15 * * MON-FRI";
  }

  @Data
  public static class WishPool {
    private boolean enabled = false;
    private String webhookUrl;
    private int timeoutSeconds = 10;
  }

  @Data
  public static class Monitor {
    /** 总开关 */
    private boolean enabled = true;

    /** 仅在交易时段内触发 (9:30-11:30 / 13:00-15:00) */
    private boolean requireTradingTime = true;

    /** 同一 (stock, signal) 推送冷却, 默认 10 分钟 */
    private int cooldownMinutes = 10;

    /** 每分钟扫描 cron */
    private String cron = "0 */1 9-15 * * MON-FRI";

    /** 收盘确认 cron (15:05) */
    private String confirmCron = "0 5 15 * * MON-FRI";

    /** 默认 Server酱 模板 */
    private String defaultTemplate = "standard";

    /** 要扫描的 pool 类型集合（投资池正式值为 invest；历史别名 stock 会被归一化） */
    private List<String> poolTypes = new ArrayList<>(List.of("invest", "tech_ai", "potential"));
  }
}
