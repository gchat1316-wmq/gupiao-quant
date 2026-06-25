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

    /** Kimi CLI 配置 (保留兼容字段) */
    private KimiCli kimiCli = new KimiCli();

    /** News Radar 配置 */
    private NewsRadar newsRadar = new NewsRadar();

    /** A-Stock-Data 数据抓取配置 */
    private DataFetch dataFetch = new DataFetch();

    /** 投研 PDF 爬取配置 */
    private PdfFetch pdfFetch = new PdfFetch();

    /** LLM CLI 模式（true=走 minimax/llm_call.py 子进程；false=走 MiniMaxClient 直连） */
    private CliMode cliMode = new CliMode();

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

    @Data
    public static class PdfFetch {
        /** 是否启用 PDF 抓取 */
        private boolean enabled = true;
        /** 抓取后保留的最大研报数 */
        private Integer maxReports = 4;
        /** Tavily 搜索返回的最大结果数 */
        private Integer maxResults = 8;
        /** 单篇研报最大字符数 */
        private Integer perReportMaxChars = 8000;
        /** 喂给 LLM 的总文本最大字符数 */
        private Integer maxReportChars = 24000;
        /** PDF 下载超时（秒） */
        private Integer downloadTimeoutSeconds = 20;
    }

    @Data
    public static class CliMode {
        /** 是否启用 LLM CLI 模式（true=走子进程调 llm_call.py；false=走 MiniMaxClient） */
        private boolean enabled = false;
        /** llm_call.py 路径，默认指向 mavis 内置 skill 脚本 */
        private String scriptPath = "${user.home}/.mavis/.builtin-skills/llm-call/scripts/llm_call.py";
        /** Python 解释器 */
        private String pythonCommand = "python3";
        /** 调用模型，格式 provider/model；默认 MiniMax-M2.7-highspeed */
        private String model = "minimax-test/MiniMax-M2.7-highspeed";
        /** 单次调用最大 token */
        private Integer maxTokens = 8192;
        /** 超时（秒） */
        private Integer timeoutSeconds = 600;
    }
}