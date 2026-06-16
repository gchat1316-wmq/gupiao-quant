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
    private String cron = "0 30 15 * * MON-FRI";

    /** 每日 Top N 板块 */
    private int maxSectors = 5;

    /** 每个板块取 Top N 候选龙头 */
    private int leadersPerSector = 5;

    /** 候选清单最终保留上限 */
    private int maxCandidates = 15;

    /** 默认数据链路: local / wind / tdx / hybrid */
    private String provider = "local";

    /** AI 调用开关 */
    private Ai ai = new Ai();

    /** 板块抓取数据源开关 */
    private Source source = new Source();

    /** Wind AI 金融终端 Skill 链路 */
    private Wind wind = new Wind();

    /** 通达信 MCP 链路 */
    private Tdx tdx = new Tdx();

    @Data
    public static class Ai {
        /** AI 是否启用（关闭时只输出基础叙事） */
        private boolean enabled = true;
        /** 失败时是否回退到 mock */
        private boolean fallbackToMock = true;
    }

    @Data
    public static class Source {
        /** eastmoney / a_stock_data / local */
        private String sector = "eastmoney";
        /** 网络超时秒数 */
        private int timeoutSeconds = 15;
    }

    @Data
    public static class Wind {
        /** 全局安装后的 wind-mcp-skill 目录 */
        private String skillDir = System.getProperty("user.home") + "/.agents/skills/wind-mcp-skill";
        /** 全局 API key 配置文件,格式: WIND_API_KEY=... */
        private String configPath = System.getProperty("user.home") + "/.wind-aifinmarket/config";
        /** CLI 调用超时秒数 */
        private int timeoutSeconds = 15;
    }

    @Data
    public static class Tdx {
        /** 当前 Java 应用是否直接启用通达信 MCP 调用 */
        private boolean enabled = false;
        /** WorkBuddy 通达信 connector 安装目录,用于能力探测 */
        private String connectorDir = System.getProperty("user.home")
                + "/.workbuddy/connectors-marketplace/connectors/tdx-connector";
        /** 通达信 MCP 服务地址 */
        private String mcpUrl = "https://txmcp.tdx.com.cn:3001/txmcp";
    }
}
