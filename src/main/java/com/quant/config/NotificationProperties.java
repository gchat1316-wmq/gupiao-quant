package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private ServerChan serverchan = new ServerChan();
    private PriceMonitor priceMonitor = new PriceMonitor();

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
}
