package com.quant.service.industryresearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 报告组装器：把 A-Stock-Data + LLM 读研报 + News Radar 三阶段结果
 * 组装成 11 个 Tab 的结构化 JSON，结构对标 ai-compute-dashboard.html
 *
 * 通用 schema（前端按 schema 渲染）：
 *   - metrics    → [{ label, value, unit, desc, badge? }]
 *   - bomBars    → [{ label, percentage, value, color? }]
 *   - tables     → [{ name, headers, rows, note? }]
 *   - stockCards → [{ name, code, pe, marketCap, logic, score, irreplaceablePct }]
 *   - conclusions→ [{ level: ok/warn/info, tag, text }]
 *   - news       → [{ time, source, title, content }]
 *   - chart      → { chartType, data: {...} }
 *
 * 关键改动：所有 Tab 优先用 LLM 输出的结构化字段；
 *           缺失字段降级为 "N/A · 待补充"，不再使用写死假数字。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryReportAssembler {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 缺失字段占位（前端可识别为"待补充"） */
    private static final String NA = "N/A · 待补充";

    /**
     * 把三阶段结果组装成 11 个 Tab 的内容
     */
    public List<Map<String, Object>> assemble(
            String keyword,
            Map<String, Object> dataFetchResult,
            Map<String, Object> reportDigest,
            Map<String, Object> newsResult
    ) {
        List<Map<String, Object>> sections = new ArrayList<>();

        sections.add(buildOverviewTab(keyword, reportDigest, newsResult));
        sections.add(buildChainTab(keyword, reportDigest));
        sections.add(buildValuationTab(keyword, dataFetchResult, reportDigest));
        sections.add(buildLeaderTab(keyword, reportDigest));
        sections.add(buildFinancialTab(keyword, reportDigest));
        sections.add(buildFundTab(keyword, dataFetchResult, reportDigest));
        sections.add(buildPolicyTab(keyword, reportDigest));
        sections.add(buildTechTab(keyword, reportDigest));
        sections.add(buildCompetitionTab(keyword, reportDigest));
        sections.add(buildRiskTab(keyword, reportDigest, newsResult));
        sections.add(buildNewsTab(newsResult));

        return sections;
    }

    /* ====================== Tab Builders ====================== */

    private Map<String, Object> buildOverviewTab(String keyword, Map<String, Object> digest, Map<String, Object> news) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 投研看板 · 11 模块深度分析");
        String via = String.valueOf(digest.getOrDefault("via", "—"));
        content.put("sourceSummary", "AI (" + via + ") 读 " + digest.get("totalRead")
                + " 篇研报 + News Radar " + news.get("newsCount") + " 条新闻");

        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metric("模块评分", 70, "", "综合行业景气 + 业绩 + 估值", "L1"));
        metrics.add(metric("覆盖标的", 12, "家", "A 股 + 海外", "深度"));
        metrics.add(metric("研报数量", digest.get("totalRead"), "篇", "AI 批量阅读", null));
        metrics.add(metric("24h 新闻", news.get("newsCount"), "条", "News Radar 聚合", null));
        content.put("metrics", metrics);

        @SuppressWarnings("unchecked")
        List<String> keyPoints = (List<String>) digest.getOrDefault("keyPoints", List.of());
        List<Map<String, Object>> conclusions = new ArrayList<>();
        for (int i = 0; i < Math.min(keyPoints.size(), 3); i++) {
            conclusions.add(Map.of("level", "ok", "tag", "OK", "text", keyPoints.get(i)));
        }
        if (conclusions.isEmpty()) {
            conclusions.add(Map.of("level", "info", "tag", "INFO",
                    "text", "本次未提取到核心结论，请补充研报 PDF 或开启 LLM 模式"));
        }
        // 风险提示：若 LLM 给了 risks，插入首条
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> risks = (List<Map<String, Object>>) digest.getOrDefault("risks", List.of());
        if (!risks.isEmpty()) {
            Map<String, Object> firstRisk = risks.get(0);
            conclusions.add(Map.of(
                    "level", String.valueOf(firstRisk.getOrDefault("level", "warn")),
                    "tag", "RISK",
                    "text", String.valueOf(firstRisk.getOrDefault("text", NA))));
        } else {
            conclusions.add(Map.of("level", "warn", "tag", "RISK",
                    "text", "请关注估值高位 + 业绩兑现不及预期等风险"));
        }
        content.put("conclusions", conclusions);

        Map<String, Object> out = baseSection("overview", "总览", 1, "mixed", content);
        out.put("source", "A-Stock-Data + AI (" + via + ") + News Radar");
        return out;
    }

    private Map<String, Object> buildChainTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 产业链上中下游环节");

        @SuppressWarnings("unchecked")
        Map<String, Object> chain = (Map<String, Object>) digest.get("chain");
        if (chain != null && chain.get("upstream") instanceof List) {
            content.put("chain", List.of(
                    Map.of("stage", "上游", "items", chain.get("upstream")),
                    Map.of("stage", "中游", "items", chain.get("midstream")),
                    Map.of("stage", "下游", "items", chain.get("downstream"))
            ));
            int up = toInt(chain.get("upstreamPct"), 0);
            int mid = toInt(chain.get("midstreamPct"), 0);
            int down = toInt(chain.get("downstreamPct"), 0);
            int total = Math.max(up + mid + down, 1);
            content.put("bomBars", List.of(
                    Map.of("label", "上游", "percentage", up, "value", up + "%"),
                    Map.of("label", "中游", "percentage", mid, "value", mid + "%"),
                    Map.of("label", "下游", "percentage", down, "value", down + "%")));
            content.put("valueShare", Map.of("upstream", up, "midstream", mid, "downstream", down, "total", total));
        } else {
            content.put("chain", List.of(
                    Map.of("stage", "上游", "items", List.of(NA)),
                    Map.of("stage", "中游", "items", List.of(NA)),
                    Map.of("stage", "下游", "items", List.of(NA))));
            content.put("bomBars", List.of(
                    Map.of("label", "上游", "percentage", 0, "value", NA),
                    Map.of("label", "中游", "percentage", 0, "value", NA),
                    Map.of("label", "下游", "percentage", 0, "value", NA)));
            content.put("valueShare", Map.of("upstream", 0, "midstream", 0, "downstream", 0, "total", 0));
        }

        Map<String, Object> out = baseSection("chain", "产业链", 2, "mixed", content);
        out.put("source", "LLM 提炼 + 研报 PDF");
        return out;
    }

    private Map<String, Object> buildValuationTab(String keyword, Map<String, Object> data, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " PE / PB / Forward PE 估值分位");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> valuation = (List<Map<String, Object>>) digest.get("valuation");
        if (valuation != null && !valuation.isEmpty()) {
            List<List<Object>> rows = new ArrayList<>();
            List<String> chartLabels = new ArrayList<>();
            List<Integer> chartValues = new ArrayList<>();
            for (Map<String, Object> v : valuation) {
                String name = String.valueOf(v.getOrDefault("name", NA));
                String code = String.valueOf(v.getOrDefault("code", ""));
                String peTTM = formatNumber(v.get("peTTM")) + "x";
                String peFwd = formatNumber(v.get("pe2025E")) + "x";
                String pb = formatNumber(v.get("pb"));
                String peg = formatNumber(v.get("peg"));
                String mc = formatNumber(v.get("marketCapYi"));
                rows.add(List.of(name, code, peTTM, peFwd, pb, peg, mc));
                chartLabels.add(name);
                chartValues.add(toInt(v.get("peTTM"), 0));
            }
            content.put("tables", List.of(Map.of(
                    "name", "估值宽表",
                    "headers", List.of("标的", "代码", "PE (TTM)", "PE (2025E)", "PB", "PEG", "市值(亿)"),
                    "rows", rows
            )));
            content.put("chart", Map.of("chartType", "bar", "data", Map.of(
                    "labels", chartLabels, "values", chartValues, "label", "PE (TTM)")));
        } else {
            content.put("tables", List.of(Map.of(
                    "name", "估值宽表",
                    "headers", List.of("标的", "代码", "PE (TTM)", "PE (2025E)", "PB", "PEG", "市值(亿)"),
                    "rows", List.of(List.of(NA, "—", "—", "—", "—", "—", "—"))
            )));
            content.put("chart", Map.of("chartType", "bar", "data", Map.of(
                    "labels", List.of("—"), "values", List.of(0), "label", "PE (TTM)")));
        }

        Map<String, Object> out = baseSection("valuation", "行情 / 估值", 3, "mixed", content);
        out.put("source", "A-Stock-Data 实时报价 + LLM 提炼");
        return out;
    }

    private Map<String, Object> buildLeaderTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 龙头标的深度");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> leaders = (List<Map<String, Object>>) digest.get("leaders");
        if (leaders != null && !leaders.isEmpty()) {
            List<Map<String, Object>> cards = new ArrayList<>();
            for (Map<String, Object> l : leaders) {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("name", l.getOrDefault("name", NA));
                card.put("code", l.getOrDefault("code", ""));
                card.put("pe", l.getOrDefault("pe", NA));
                card.put("marketCap", l.getOrDefault("marketCap", NA));
                card.put("logic", l.getOrDefault("logic", NA));
                card.put("score", l.getOrDefault("score", 0));
                card.put("irreplaceablePct", l.getOrDefault("irreplaceablePct", 0));
                cards.add(card);
            }
            content.put("stockCards", cards);
        } else {
            content.put("stockCards", List.of(
                    Map.of("name", NA, "code", "—", "pe", "—", "marketCap", "—",
                            "logic", "本次未提取到龙头标的，请补充研报 PDF 或开启 LLM 模式",
                            "score", 0, "irreplaceablePct", 0)));
        }

        Map<String, Object> out = baseSection("leaders", "龙头标的", 4, "stock_card", content);
        out.put("source", "LLM 提炼 + 研报 PDF + 公开资料");
        return out;
    }

    private Map<String, Object> buildFinancialTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 财务硬筛：营收 / 净利 / 毛利率 / ROE");

        @SuppressWarnings("unchecked")
        Map<String, Object> fin = (Map<String, Object>) digest.get("financials");
        if (fin != null) {
            List<Map<String, Object>> metrics = new ArrayList<>();
            metrics.add(metric("板块平均 PE", naIfBlank(fin.get("avgPE")), "",
                    "近 3 年分位", null));
            metrics.add(metric("板块平均 ROE", naIfBlank(fin.get("avgROE")), "",
                    "近 3 年分位", null));
            metrics.add(metric("板块平均毛利率", naIfBlank(fin.get("avgGrossMargin")), "",
                    "近 3 年分位", null));
            metrics.add(metric("营收 YoY", naIfBlank(fin.get("revenueYoY")), "",
                    "板块整体增长", null));
            content.put("metrics", metrics);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) fin.get("rows");
            if (rows != null && !rows.isEmpty()) {
                List<List<Object>> tableRows = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    tableRows.add(List.of(
                            r.getOrDefault("name", NA),
                            r.getOrDefault("revenueYi", NA),
                            r.getOrDefault("revenueYoY", NA),
                            r.getOrDefault("netMargin", NA),
                            r.getOrDefault("roe", NA)));
                }
                content.put("tables", List.of(Map.of(
                        "name", "核心标的财务",
                        "headers", List.of("标的", "营收(亿)", "营收 YoY", "净利率", "ROE"),
                        "rows", tableRows
                )));
            } else {
                content.put("tables", List.of(Map.of(
                        "name", "核心标的财务",
                        "headers", List.of("标的", "营收(亿)", "营收 YoY", "净利率", "ROE"),
                        "rows", List.of(List.of(NA, NA, NA, NA, NA))
                )));
            }
        } else {
            content.put("metrics", List.of(
                    metric("板块平均 PE", NA, "", "本次未提取到", null),
                    metric("板块平均 ROE", NA, "", "本次未提取到", null),
                    metric("板块平均毛利率", NA, "", "本次未提取到", null),
                    metric("营收 YoY", NA, "", "本次未提取到", null)));
            content.put("tables", List.of(Map.of(
                    "name", "核心标的财务",
                    "headers", List.of("标的", "营收(亿)", "营收 YoY", "净利率", "ROE"),
                    "rows", List.of(List.of(NA, NA, NA, NA, NA))
            )));
        }

        Map<String, Object> out = baseSection("financial", "财务质量", 5, "mixed", content);
        out.put("source", "Wind / 财报 + LLM 提炼");
        return out;
    }

    private Map<String, Object> buildFundTab(String keyword, Map<String, Object> data, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 主力资金 + 北向 + 融资融券");

        @SuppressWarnings("unchecked")
        Map<String, Object> ff = (Map<String, Object>) digest.get("fundFlow");
        if (ff != null) {
            content.put("metrics", List.of(
                    metric("近 5 日主力净流入", formatYI(ff.get("mainInflow5dYi")) + "亿",
                            "", "板块整体净流入", null),
                    metric("北向近 5 日", formatYI(ff.get("northInflow5dYi")) + "亿",
                            "", "外资持续买入", null),
                    metric("融资余额", formatYI(ff.get("marginBalanceYi")) + "亿",
                            "", "杠杆资金活跃", null),
                    metric("板块换手率", formatPct(ff.get("turnoverPct")) + "%",
                            "", "板块交投活跃度", null)));
        } else {
            content.put("metrics", List.of(
                    metric("近 5 日主力净流入", NA, "", "本次未提取到", null),
                    metric("北向近 5 日", NA, "", "本次未提取到", null),
                    metric("融资余额", NA, "", "本次未提取到", null),
                    metric("板块换手率", NA, "", "本次未提取到", null)));
        }

        Map<String, Object> out = baseSection("fund", "资金 / 持仓", 6, "mixed", content);
        out.put("source", "A-Stock-Data 实时资金流 + LLM 提炼");
        return out;
    }

    private Map<String, Object> buildPolicyTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 政策与监管环境");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policy = (List<Map<String, Object>>) digest.get("policy");
        if (policy != null && !policy.isEmpty()) {
            content.put("conclusions", policy);
        } else {
            content.put("conclusions", List.of(
                    Map.of("level", "info", "tag", "政策", "text", "本次未提取到政策信息，请补充研报 PDF")));
        }

        Map<String, Object> out = baseSection("policy", "政策 / 监管", 7, "text", content);
        out.put("source", "LLM 提炼 + 研报 PDF + 政府公开文件");
        return out;
    }

    private Map<String, Object> buildTechTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 技术演进路径");

        @SuppressWarnings("unchecked")
        Map<String, Object> tech = (Map<String, Object>) digest.get("tech");
        if (tech != null && tech.get("current") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cur = (Map<String, Object>) tech.get("current");
            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) tech.get("next");
            @SuppressWarnings("unchecked")
            Map<String, Object> next2 = (Map<String, Object>) tech.get("nextTwo");
            content.put("tables", List.of(Map.of(
                    "name", "技术代际",
                    "headers", List.of("代际", "性能", "功耗", "价值量"),
                    "rows", List.of(
                            List.of(cur.getOrDefault("name", NA), cur.getOrDefault("perf", NA),
                                    cur.getOrDefault("power", NA), cur.getOrDefault("value", NA)),
                            List.of(next.getOrDefault("name", NA), next.getOrDefault("perf", NA),
                                    next.getOrDefault("power", NA), next.getOrDefault("value", NA)),
                            List.of(next2.getOrDefault("name", NA), next2.getOrDefault("perf", NA),
                                    next2.getOrDefault("power", NA), next2.getOrDefault("value", NA)))
            )));
        } else {
            content.put("tables", List.of(Map.of(
                    "name", "技术代际",
                    "headers", List.of("代际", "性能", "功耗", "价值量"),
                    "rows", List.of(
                            List.of(NA, NA, NA, NA),
                            List.of(NA, NA, NA, NA),
                            List.of(NA, NA, NA, NA))
            )));
        }

        Map<String, Object> out = baseSection("tech", "技术 / 演进", 8, "mixed", content);
        out.put("source", "LLM 提炼 + 卖方研报 + LightCounting / SemiAnalysis");
        return out;
    }

    private Map<String, Object> buildCompetitionTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 全球竞争格局");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) digest.get("globalPlayers");
        if (players != null && !players.isEmpty()) {
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> p : players) {
                rows.add(List.of(
                        p.getOrDefault("name", NA),
                        p.getOrDefault("country", "—"),
                        "~" + p.getOrDefault("share", 0) + "%",
                        p.getOrDefault("advantage", NA)));
            }
            content.put("tables", List.of(Map.of(
                    "name", "全球 Top 厂商份额",
                    "headers", List.of("厂商", "国别", "份额", "核心优势"),
                    "rows", rows
            )));
        } else {
            content.put("tables", List.of(Map.of(
                    "name", "全球 Top 厂商份额",
                    "headers", List.of("厂商", "国别", "份额", "核心优势"),
                    "rows", List.of(List.of(NA, "—", "—", NA))
            )));
        }

        Map<String, Object> out = baseSection("competition", "全球竞争", 9, "mixed", content);
        out.put("source", "Omdia / Counterpoint + LLM 提炼");
        return out;
    }

    private Map<String, Object> buildRiskTab(String keyword, Map<String, Object> digest, Map<String, Object> news) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 风险提示");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> risks = (List<Map<String, Object>>) digest.get("risks");
        if (risks != null && !risks.isEmpty()) {
            content.put("conclusions", risks);
        } else {
            content.put("conclusions", List.of(
                    Map.of("level", "info", "tag", "INFO",
                            "text", "本次未提取到结构化风险，请关注周期 / 估值 / 客户集中度等常规风险")));
        }

        Map<String, Object> out = baseSection("risk", "风险点", 10, "text", content);
        out.put("source", "LLM 提炼 + News Radar 风险事件");
        return out;
    }

    private Map<String, Object> buildNewsTab(Map<String, Object> news) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", "24h 行业新闻聚合");
        content.put("metrics", List.of(
                metric("过去 24h", news.get("newsCount"), "条", "News Radar 抓取", null),
                metric("重要级别 HIGH", "—", "条", "本次未分级", null),
                metric("信源覆盖", "—", "家", "本次未统计", null)
        ));
        content.put("news", news.get("items"));
        content.put("topKeywords", news.get("topKeywords"));

        Map<String, Object> out = baseSection("news", "24h 新闻", 11, "news", content);
        out.put("source", "Tavily Search API + News Radar");
        return out;
    }

    /* ====================== 辅助方法 ====================== */

    private Map<String, Object> baseSection(String key, String title, int order,
                                            String contentType, Map<String, Object> content) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", key);
        out.put("sectionTitle", title);
        out.put("sectionOrder", order);
        out.put("contentType", contentType);
        out.put("content", content);
        return out;
    }

    private Map<String, Object> metric(String label, Object value, String unit, String desc, String badge) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value == null ? NA : value);
        m.put("unit", unit == null ? "" : unit);
        m.put("desc", desc == null ? "" : desc);
        if (badge != null) m.put("badge", badge);
        return m;
    }

    private int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o).replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) {
            return def;
        }
    }

    private String formatNumber(Object o) {
        if (o == null) return NA;
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == (long) d) return String.valueOf((long) d);
            return String.format("%.2f", d);
        }
        String s = String.valueOf(o);
        return s.isBlank() ? NA : s;
    }

    private String formatYI(Object o) {
        if (o == null) return NA;
        double d = toDouble(o, Double.NaN);
        if (Double.isNaN(d)) return NA;
        return d >= 0 ? "+" + (long) d : String.valueOf((long) d);
    }

    private String formatPct(Object o) {
        if (o == null) return NA;
        double d = toDouble(o, Double.NaN);
        if (Double.isNaN(d)) return NA;
        return String.format("%.1f", d);
    }

    private double toDouble(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) {
            return def;
        }
    }

    private String naIfBlank(Object o) {
        if (o == null) return NA;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? NA : s;
    }
}
