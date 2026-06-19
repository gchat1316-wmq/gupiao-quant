package com.quant.service.industryresearch;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 产业投研配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "industry-research")
public class IndustryResearchProperties {

    /** 是否启用 */
    private boolean enabled = true;

    /** Python 命令（用于调用 A-Stock-Data / Kimi 脚本） */
    private String pythonCommand = "python3";

    /** Kimi CLI 配置 */
    private KimiCli kimiCli = new KimiCli();

    /** News Radar 配置 */
    private NewsRadar newsRadar = new NewsRadar();

    /** A-Stock-Data 数据抓取配置 */
    private DataFetch dataFetch = new DataFetch();

    @Data
    public static class KimiCli {
        /** 是否启用 Kimi CLI（如未启用则回退到 mock 报告） */
        private boolean enabled = false;

        /** Kimi CLI 命令（kimi / python3 -m kimi 等） */
        private String command = "kimi";

        /** 单次任务最大读取研报数 */
        private Integer maxReports = 1500;

        /** 超时（秒） */
        private Integer timeoutSeconds = 900;

        /** 单篇研报最大字符数（送入模型的截断长度） */
        private Integer maxReportChars = 50000;

        /** prompt 模板路径（resources/prompts/industry-research-v1.txt） */
        private String promptTemplate = "prompts/industry-research-v1.txt";
    }

    @Data
    public static class NewsRadar {
        /** 是否启用 News Radar 真实抓取 */
        private boolean enabled = true;
        /** 抓取小时窗口 */
        private Integer hours = 24;
        /** 单次最大结果数 */
        private Integer maxResults = 20;
        /** 关键词过滤（关键词 OR 关系） */
        private String filterKeywords = "";
    }

    @Data
    public static class DataFetch {
        /** A-Stock-Data 超时 */
        private Integer timeoutSeconds = 30;
        /** 是否尝试本地 BaoStock 兜底 */
        private boolean fallbackLocal = true;
    }
}