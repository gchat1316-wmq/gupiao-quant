package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private ServerChan serverchan = new ServerChan();
    private PriceMonitor priceMonitor = new PriceMonitor();
    private QuoteMonitor quoteMonitor = new QuoteMonitor();
    private WishPool wishPool = new WishPool();

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
}
