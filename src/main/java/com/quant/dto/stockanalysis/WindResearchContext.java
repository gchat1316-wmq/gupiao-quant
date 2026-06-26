package com.quant.dto.stockanalysis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wind 研报上下文：在 StockAnalysisService 主流程里被拉一次（24h 缓存），
 * 同时塞进 prompt（强制 AI 引用一致预期） 和 response 字段（前端渲染）。
 *
 * 字段命名严格遵守 wind-mcp-skill 返回结构：
 *   - analytics_data.get_financial_data 的 rows[].columns
 *   - financial_docs.get_financial_news 的 items[].title/content/date/relevance
 *
 * 失败/无 Key 时 available=false，prompt 注入 "（Wind 研报：未启用）"，主报告不受影响。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WindResearchContext {

    /** Wind 是否真的拿到了数据（Key 在 + 至少 1 条 consensus 或 report） */
    private boolean available;

    /** Wind skill 是否安装/有 Key（仅作降级标注, 不影响 available） */
    private boolean windInstalled;
    private boolean windHasKey;

    /** 触发的方法（full/purple_perilla/gaojingqi/five_dimension）— 前端按方法决定渲染位置 */
    private String method;

    /** 一致预期：来自 analytics_data.get_financial_data */
    private Consensus consensus;

    /** 研报片段列表：来自 financial_docs.get_financial_news 搜"X 研报/深度报告/投资逻辑" */
    private List<ResearchExcerpt> reports;

    /** 缓存命中时间（毫秒） */
    private Long cachedAt;
    /** 拉取耗时（毫秒） */
    private Long elapsedMs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Consensus {
        /** 综合评级（来自 Wind 卖方一致预期, 例如 "增持"/"买入"/"中性"） */
        private String rating;
        /** 一致预期目标价（单位:元，可能为 null 表示 Wind 没返回具体数） */
        private Double targetPrice;
        /** 货币（CNY/HKD 等） */
        private String currency;
        /** 一致预期 2026 EPS（来自 Wind） */
        private Double eps2026;
        /** 一致预期 2027 EPS（来自 Wind） */
        private Double eps2027;
        /** 一致预期 2026 净利润同比（%） */
        private Double netProfitGrowth2026;
        /** 一致预期 2027 净利润同比（%） */
        private Double netProfitGrowth2027;
        /** 原始 resolved_question（debug 用） */
        private String resolvedQuestion;
        /** Wind 实际返回的源数据条数（0 表示未拉到） */
        private int sourceRowCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResearchExcerpt {
        /** 标题 */
        private String title;
        /** 摘要（已截断到 500 字内） */
        private String content;
        /** 发布日期（yyyy-MM-dd） */
        private String date;
        /** doc_type: news / announcement / research */
        private String docType;
        /** 0~1 相关性 */
        private Double relevance;
        /** 推断的发布机构（券商名）, 解析自 content 头部 "来源: XX" 段 */
        private String source;
    }
}
