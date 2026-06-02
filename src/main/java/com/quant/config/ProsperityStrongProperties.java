package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "prosperity-strong")
public class ProsperityStrongProperties {

    /** 是否启用整个流水线 */
    private boolean enabled = true;

    /** 定时 cron */
    private String cron = "0 30 16 * * MON-FRI";

    /** 每日 Top N 板块 */
    private int maxSectors = 5;

    /** 每个板块取 Top N 候选龙头 */
    private int leadersPerSector = 5;

    /** 候选清单最终保留上限 */
    private int maxCandidates = 15;

    /** AI 调用开关 */
    private Ai ai = new Ai();

    /** 板块抓取数据源开关 */
    private Source source = new Source();

    @Data
    public static class Ai {
        /** AI 是否启用（关闭时只输出基础叙事） */
        private boolean enabled = true;
        /** 失败时是否回退到 mock */
        private boolean fallbackToMock = true;
    }

    @Data
    public static class Source {
        /** eastmoney / local */
        private String sector = "eastmoney";
        /** 网络超时秒数 */
        private int timeoutSeconds = 15;
    }
}
