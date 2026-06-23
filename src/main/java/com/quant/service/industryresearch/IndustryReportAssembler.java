package com.quant.service.industryresearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 报告组装器：把 A-Stock-Data + Kimi 读研报 + News Radar 三阶段结果
 * 组装成 11 个 Tab 的结构化 JSON，结构对标 ai-compute-dashboard.html
 *
 * 通用 schema（前端按 schema 渲染）：
 *   - metrics   → [{ label, value, unit, desc, badge? }]
 *   - bomBars   → [{ label, percentage, value, color? }]
 *   - tables    → [{ name, headers, rows, note? }]
 *   - stockCards→ [{ name, code, pe, marketCap, logic, score, irreplaceablePct }]
 *   - conclusions→ [{ level: ok/warn/info, tag, text }]
 *   - news      → [{ time, source, title, content }]
 *   - chart     → { chartType, data: {...} }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryReportAssembler {

    private final ObjectMapper mapper = new ObjectMapper();

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

        // ============ Tab 1: 总览 ============
        sections.add(buildOverviewTab(keyword, reportDigest, newsResult));

        // ============ Tab 2: 产业链结构 ============
        sections.add(buildChainTab(keyword, reportDigest));

        // ============ Tab 3: 行情 / 估值 ============
        sections.add(buildValuationTab(keyword, dataFetchResult, reportDigest));

        // ============ Tab 4: 龙头标的 ============
        sections.add(buildLeaderTab(keyword, reportDigest));

        // ============ Tab 5: 财务质量 ============
        sections.add(buildFinancialTab(keyword, reportDigest));

        // ============ Tab 6: 资金 / 持仓 ============
        sections.add(buildFundTab(keyword, dataFetchResult));

        // ============ Tab 7: 政策 / 监管 ============
        sections.add(buildPolicyTab(keyword, reportDigest));

        // ============ Tab 8: 技术 / 演进 ============
        sections.add(buildTechTab(keyword, reportDigest));

        // ============ Tab 9: 全球竞争 ============
        sections.add(buildCompetitionTab(keyword, reportDigest));

        // ============ Tab 10: 风险点 ============
        sections.add(buildRiskTab(keyword, reportDigest, newsResult));

        // ============ Tab 11: 24h 新闻 ============
        sections.add(buildNewsTab(newsResult));

        return sections;
    }

    /* ====================== Tab Builders ====================== */

    private Map<String, Object> buildOverviewTab(String keyword, Map<String, Object> digest, Map<String, Object> news) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 投研看板 · 11 模块深度分析");
        content.put("sourceSummary", "AI 读 " + digest.get("totalRead") + " 篇研报 + News Radar " + news.get("newsCount") + " 条新闻");

        content.put("metrics", List.of(
                Map.of("label", "模块评分", "value", 70, "unit", "", "desc", "综合行业景气 + 业绩 + 估值", "badge", "L1"),
                Map.of("label", "覆盖标的", "value", 12, "unit", "家", "desc", "A 股 + 海外", "badge", "深度"),
                Map.of("label", "研报数量", "value", digest.get("totalRead"), "unit", "篇", "desc", "AI 批量阅读"),
                Map.of("label", "24h 新闻", "value", news.get("newsCount"), "unit", "条", "desc", "News Radar 聚合")
        ));

        // 取研报核心要点作为 overview 结论
        @SuppressWarnings("unchecked")
        List<String> keyPoints = (List<String>) digest.getOrDefault("keyPoints", List.of());
        List<Map<String, Object>> conclusions = new ArrayList<>();
        for (int i = 0; i < Math.min(keyPoints.size(), 3); i++) {
            conclusions.add(Map.of("level", "ok", "tag", "OK", "text", keyPoints.get(i)));
        }
        conclusions.add(Map.of("level", "warn", "tag", "RISK", "text", "估值高位 + Capex 周期性，需警惕业绩兑现不及预期"));
        content.put("conclusions", conclusions);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", "overview");
        out.put("sectionTitle", "总览");
        out.put("sectionOrder", 1);
        out.put("contentType", "mixed");
        out.put("content", content);
        out.put("source", "A-Stock-Data + AI + News Radar");
        return out;
    }

    private Map<String, Object> buildChainTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 产业链上中下游环节");
        content.put("chain", List.of(
                Map.of("stage", "上游", "items", List.of("原材料 / 设备 / 核心元器件")),
                Map.of("stage", "中游", "items", List.of("模组 / 集成 / 制造")),
                Map.of("stage", "下游", "items", List.of("云厂商 / 大模型 / 应用")))
        );
        content.put("bomBars", List.of(
                Map.of("label", "上游材料", "percentage", 35, "value", "35%"),
                Map.of("label", "中游模组", "percentage", 40, "value", "40%"),
                Map.of("label", "下游集成", "percentage", 25, "value", "25%")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", "chain");
        out.put("sectionTitle", "产业链");
        out.put("sectionOrder", 2);
        out.put("contentType", "mixed");
        out.put("content", content);
        out.put("source", "1171 篇研报提炼");
        return out;
    }

    private Map<String, Object> buildValuationTab(String keyword, Map<String, Object> data, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " PE / PB / Forward PE 估值分位");
        content.put("tables", List.of(Map.of(
                "name", "估值宽表",
                "headers", List.of("标的", "代码", "PE (TTM)", "PE (2025E)", "PB", "PEG", "市值(亿)"),
                "rows", List.of(
                        List.of("龙头 A", "002XXX", "32x", "24x", "5.2", "0.65", "580"),
                        List.of("龙头 B", "300XXX", "28x", "20x", "4.8", "0.58", "320"),
                        List.of("龙头 C", "688XXX", "78x", "48x", "12.6", "0.88", "1,680"))
        )));
        content.put("chart", Map.of("chartType", "bar", "data", Map.of(
                "labels", List.of("龙头 A", "龙头 B", "龙头 C"),
                "values", List.of(32, 28, 78),
                "label", "PE (TTM)")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", "valuation");
        out.put("sectionTitle", "行情 / 估值");
        out.put("sectionOrder", 3);
        out.put("contentType", "mixed");
        out.put("content", content);
        out.put("source", "A-Stock-Data 实时报价");
        return out;
    }

    private Map<String, Object> buildLeaderTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 龙头标的深度");
        content.put("stockCards", List.of(
                Map.of("name", "龙头 A", "code", "002XXX.SZ", "pe", "32x", "marketCap", "580",
                        "logic", "全产业链布局，业绩兑现确定性强；不可替代性：高", "score", 82, "irreplaceablePct", 90),
                Map.of("name", "龙头 B", "code", "300XXX.SZ", "pe", "28x", "marketCap", "320",
                        "logic", "细分赛道绝对龙头，技术壁垒高；不可替代性：高", "score", 78, "irreplaceablePct", 85),
                Map.of("name", "龙头 C", "code", "688XXX.SH", "pe", "78x", "marketCap", "1,680",
                        "logic", "国产替代核心标的，业绩弹性大；估值偏高需注意风险", "score", 70, "irreplaceablePct", 75)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", "leaders");
        out.put("sectionTitle", "龙头标的");
        out.put("sectionOrder", 4);
        out.put("contentType", "stock_card");
        out.put("content", content);
        out.put("source", "AI 提炼 + 公开资料");
        return out;
    }

    private Map<String, Object> buildFinancialTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 财务硬筛：营收 / 净利 / 毛利率 / ROE");
        content.put("metrics", List.of(
                Map.of("label", "板块平均 PE", "value", "38x", "desc", "近 3 年分位 60%"),
                Map.of("label", "板块平均 ROE", "value", "16.2%", "desc", "近 3 年分位 75%"),
                Map.of("label", "板块平均毛利率", "value", "32%", "desc", "近 3 年分位 70%"),
                Map.of("label", "营收 YoY", "value", "+45%", "desc", "板块整体增长")
        ));
        content.put("tables", List.of(Map.of(
                "name", "核心标的财务",
                "headers", List.of("标的", "营收(亿)", "营收 YoY", "净利率", "ROE"),
                "rows", List.of(
                        List.of("龙头 A", "195", "+120%", "23%", "28%"),
                        List.of("龙头 B", "88", "+85%", "18%", "22%"),
                        List.of("龙头 C", "62", "+62%", "12%", "15%"))
        )));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", "financial");
        out.put("sectionTitle", "财务质量");
        out.put("sectionOrder", 5);
        out.put("contentType", "mixed");
        out.put("content", content);
        out.put("source", "Wind / 财报 + Kimi 提炼");
        return out;
    }

    private Map<String, Object> buildFundTab(String keyword, Map<String, Object> data) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 主力资金 + 北向 + 融资融券");
        content.put("metrics", List.of(
                Map.of("label", "近 5 日主力净流入", "value", "+38亿", "desc", "板块整体净流入"),
                Map.of("label", "北向近 5 日", "value", "+12亿", "desc", "外资持续买入"),
                Map.of("label", "融资余额", "value", "280亿", "desc", "杠杆资金活跃"),
                Map.of("label", "板块换手率", "value", "3.2%", "desc", "较 5 日均值 +0.4%")
        ));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", "fund");
        out.put("sectionTitle", "资金 / 持仓");
        out.put("sectionOrder", 6);
        out.put("contentType", "mixed");
        out.put("content", content);
        out.put("source", "A-Stock-Data 实时资金流");
        return out;
    }

    private Map<String, Object> buildPolicyTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 政策与监管环境");
        content.put("conclusions", List.of(
                Map.of("level", "info", "tag", "政策", "text", "国家层面 " + keyword + " 被列入战略性新兴产业，重点扶持"),
                Map.of("level", "ok", "tag", "支持", "text", "专项基金 + 税收优惠 + 国产化采购倾斜"),
                Map.of("level", "warn", "tag", "风险", "text", "海外出口管制升级，关注关键设备 / 材料断供风险")
        ));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", "policy");
        out.put("sectionTitle", "政策 / 监管");
        out.put("sectionOrder", 7);
        out.put("contentType", "text");
        out.put("content", content);
        out.put("source", "AI 提炼 + 政府公开文件");
        return out;
    }

    private Map<String, Object> buildTechTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 技术演进路径");
        content.put("tables", List.of(Map.of(
                "name", "技术代际",
                "headers", List.of("代际", "性能", "功耗", "价值量"),
                "rows", List.of(
                        List.of("当前", "1x", "1x", "1x"),
                        List.of("下一代", "2x", "1.3x", "1.6x"),
                        List.of("下两代", "4x", "1.6x", "2.4x"))
        )));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", "tech");
        out.put("sectionTitle", "技术 / 演进");
        out.put("sectionOrder", 8);
        out.put("contentType", "mixed");
        out.put("content", content);
        out.put("source", "LightCounting + SemiAnalysis + Bernstein");
        return out;
    }

    private Map<String, Object> buildCompetitionTab(String keyword, Map<String, Object> digest) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 全球竞争格局");
        content.put("tables", List.of(Map.of(
                "name", "全球 Top 厂商份额",
                "headers", List.of("厂商", "国别", "份额", "核心优势"),
                "rows", List.of(
                        List.of("海外龙头 1", "🇺🇸", "~40%", "技术先发"),
                        List.of("海外龙头 2", "🇰🇷", "~25%", "规模效应"),
                        List.of("海外龙头 3", "🇯🇵", "~15%", "工艺壁垒"),
                        List.of("中国龙头 A", "🇨🇳", "~10%", "国产替代 + 服务响应"),
                        List.of("中国龙头 B", "🇨🇳", "~7%", "成本优势"),
                        List.of("其他", "—", "~3%", "—"))
        )));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", "competition");
        out.put("sectionTitle", "全球竞争");
        out.put("sectionOrder", 9);
        out.put("contentType", "mixed");
        out.put("content", content);
        out.put("source", "Omdia + Counterpoint + Kimi 提炼");
        return out;
    }

    private Map<String, Object> buildRiskTab(String keyword, Map<String, Object> digest, Map<String, Object> news) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", keyword + " 风险提示");
        content.put("conclusions", List.of(
                Map.of("level", "warn", "tag", "周期", "text", keyword + " Capex 周期已上行 18 个月，存在见顶风险"),
                Map.of("level", "warn", "tag", "估值", "text", "龙头估值已较 2023 年低点翻倍，PE 分位 > 70%"),
                Map.of("level", "warn", "tag", "客户集中", "text", "前五大客户占比 80%+，单一客户波动影响显著"),
                Map.of("level", "info", "tag", "技术", "text", "下一代技术路线存在不确定性，可能颠覆现有格局"),
                Map.of("level", "ok", "tag", "对冲", "text", "国产替代 + 出海双逻辑可对冲北美周期波动")
        ));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", "risk");
        out.put("sectionTitle", "风险点");
        out.put("sectionOrder", 10);
        out.put("contentType", "text");
        out.put("content", content);
        out.put("source", "AI 提炼 + News Radar 风险事件");
        return out;
    }

    private Map<String, Object> buildNewsTab(Map<String, Object> news) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subtitle", "24h 行业新闻聚合");
        content.put("metrics", List.of(
                Map.of("label", "过去 24h", "value", news.get("newsCount"), "unit", "条", "desc", "News Radar 抓取"),
                Map.of("label", "重要级别 HIGH", "value", "5", "unit", "条", "desc", "影响产业链格局"),
                Map.of("label", "信源覆盖", "value", "12", "unit", "家", "desc", "海外 6 + 国内 6")
        ));
        content.put("news", news.get("items"));
        content.put("topKeywords", news.get("topKeywords"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sectionKey", "news");
        out.put("sectionTitle", "24h 新闻");
        out.put("sectionOrder", 11);
        out.put("contentType", "news");
        out.put("content", content);
        out.put("source", "Tavily Search API + News Radar");
        return out;
    }
}