package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.StockAnalysisProperties;
import com.quant.dto.stockanalysis.StockAnalysisRequest;
import com.quant.dto.stockanalysis.StockAnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 个股分析服务
 * - 调 baostock-finance-data skill 拉数据
 * - 应用紫苏叶 + 高景气九维方法论输出研报
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAnalysisService {

    private final StockAnalysisProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StockAnalysisResponse analyze(StockAnalysisRequest req) {
        long start = System.currentTimeMillis();
        String code = normalizeCode(req.getCode());
        String method = req.getMethod() == null ? "full" : req.getMethod();

        // 1. 拉数据
        Map<String, Object> rawData = fetchPack(code, req);
        if (rawData == null || rawData.isEmpty()) {
            return StockAnalysisResponse.builder()
                    .ok(false)
                    .code(code)
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        // 2. 解析基础信息
        Map<String, Object> basic = asMap(rawData.get("basic"));
        String name = basic == null ? code : String.valueOf(basic.getOrDefault("code_name", code));

        Map<String, Object> quote = asMap(rawData.get("quote"));
        Double price = quote == null ? null : parseDouble(quote.get("close"));

        // 3. 财务摘要
        Map<String, Object> financialSummary = buildFinancialSummary(asList(rawData.get("financial_history")));

        // 4. 紫苏叶方法
        Map<String, Object> chainPosition = null;
        Map<String, Object> competition = null;
        Map<String, Object> threeQuestions = null;
        Integer moatScore = null;
        String verdict = null;

        if ("purple_perilla".equals(method) || "full".equals(method)) {
            Map<String, Object> pcr = runPurplePerilla(rawData, name);
            chainPosition = asMap(pcr.get("chainPosition"));
            competition = asMap(pcr.get("competition"));
            threeQuestions = asMap(pcr.get("threeQuestions"));
            moatScore = (Integer) pcr.get("moatScore");
            verdict = (String) pcr.get("verdict");
        }

        // 5. 高景气九维
        Map<String, Object> nineDim = null;
        if ("gaojingqi".equals(method) || "full".equals(method)) {
            nineDim = runGaoJingQi(rawData, name, price);
        }

        // 6. 催化剂与风险
        List<String> catalysts = buildCatalysts(rawData, name);
        List<String> risks = buildRisks(rawData, name);

        return StockAnalysisResponse.builder()
                .ok(true)
                .code(code)
                .name(name)
                .currentPrice(price)
                .method(method)
                .verdict(verdict)
                .moatScore(moatScore)
                .chainPosition(chainPosition)
                .financialSummary(financialSummary)
                .competition(competition)
                .threeQuestions(threeQuestions)
                .nineDimension(nineDim)
                .catalysts(catalysts)
                .risks(risks)
                .rawData(rawData)
                .timestamp(LocalDateTime.now())
                .elapsedMs(System.currentTimeMillis() - start)
                .build();
    }

    // ============================================================
    // 1. 拉 baostock pack
    // ============================================================
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchPack(String code, StockAnalysisRequest req) {
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    properties.getPythonCommand(),
                    properties.getPythonScript(),
                    "pack", code,
                    String.valueOf(req.getQuoteDays() == null ? 60 : req.getQuoteDays()),
                    String.valueOf(req.getYears() == null ? 2 : req.getYears())
            ));
            if (Boolean.TRUE.equals(req.getLite())) {
                cmd.add("--lite");
            }
            log.info("调 baostock: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line);
                }
            }
            boolean done = process.waitFor(properties.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                log.error("baostock 调用超时 ({}s)", properties.getTimeoutSeconds());
                return null;
            }
            if (process.exitValue() != 0) {
                log.error("baostock 退出码非0: {}", stdout);
                return null;
            }
            // 找 JSON 起点 (跳过 login success! 等前缀)
            String content = stdout.toString();
            int idx = content.indexOf('{');
            if (idx < 0) {
                log.error("baostock 输出无 JSON");
                return null;
            }
            JsonNode root = objectMapper.readTree(content.substring(idx));
            return objectMapper.convertValue(root, Map.class);
        } catch (Exception e) {
            log.error("baostock 调用失败", e);
            return null;
        }
    }

    // ============================================================
    // 2. 紫苏叶方法
    // ============================================================
    private Map<String, Object> runPurplePerilla(Map<String, Object> raw, String name) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> industry = asMap(raw.get("industry"));
        String industryName = industry == null ? "未知" : String.valueOf(industry.getOrDefault("industry", "未知"));

        // 1. 产业链位置 (基于行业名称启发式判断)
        Map<String, Object> chain = new HashMap<>();
        chain.put("industry", industryName);
        chain.put("name", name);
        chain.put("layer", inferLayer(industryName, name));
        chain.put("chainPath", inferChainPath(industryName, name));
        chain.put("moatType", inferMoatType(industryName, name));
        result.put("chainPosition", chain);

        // 2. 竞争格局
        Map<String, Object> comp = new HashMap<>();
        comp.put("globalPlayers", inferCompetitors(industryName, name));
        comp.put("chinesePosition", inferChinesePosition(industryName, name));
        comp.put("geographicAdvantage", inferGeoAdvantage(industryName, name));
        result.put("competition", comp);

        // 3. 三个问题清单
        Map<String, Object> q = new HashMap<>();
        q.put("Q1_irreplaceable", "需要核实 - 该环节全球供应商数量与精智达对标分析");
        q.put("Q2_competitorCount", "需要核实 - 国内/全球具体玩家数");
        q.put("Q3_demandTrend", "需要核实 - 下游Capex订单趋势");
        q.put("note", "本数据为占位提示, 需结合个股非结构化调研");
        result.put("threeQuestions", q);

        // 4. 护城河打分
        int moat = calcMoat(industryName, name);
        result.put("moatScore", moat);

        // 5. 投资判定
        String verdict;
        if (moat >= 8) verdict = "盯住/就是它了";
        else if (moat >= 6) verdict = "盯住";
        else if (moat >= 4) verdict = "观望";
        else verdict = "回避";
        result.put("verdict", verdict);

        return result;
    }

    // ============================================================
    // 3. 高景气九维
    // ============================================================
    private Map<String, Object> runGaoJingQi(Map<String, Object> raw, String name, Double price) {
        Map<String, Object> nine = new HashMap<>();
        List<Object> finHistory = asList(raw.get("financial_history"));

        // 1. 财务质量 (基于真实数据)
        Map<String, Object> fin = new HashMap<>();
        if (!finHistory.isEmpty()) {
            Map<String, Object> latest = asMap(finHistory.get(finHistory.size() - 1));
            Map<String, Object> prof = asMap(latest.get("profitability"));
            Map<String, Object> growth = asMap(latest.get("growth"));
            fin.put("latestPeriod", latest.get("statDate"));
            fin.put("revenue", parseDouble(prof == null ? null : prof.get("revenue")));
            fin.put("roe", formatPct(prof == null ? null : prof.get("roe_avg")));
            fin.put("grossMargin", formatPct(prof == null ? null : prof.get("gp_margin")));
            fin.put("netMargin", formatPct(prof == null ? null : prof.get("np_margin")));
            fin.put("yoyRevenue", formatPct(growth == null ? null : growth.get("yoy_revenue")));
            fin.put("yoyNetProfit", formatPct(growth == null ? null : growth.get("yoy_ni")));
            fin.put("epsTtm", parseDouble(prof == null ? null : prof.get("eps_ttm")));
        }
        nine.put("financial", fin);

        // 2. 估值 (baostock无 PE/PB, 标注 N/A 提示)
        Map<String, Object> valuation = new HashMap<>();
        valuation.put("currentPrice", price);
        valuation.put("peTtm", "N/A (需用 eastmoney / Wind)");
        valuation.put("note", "Baostock 不提供 PE/PB/PS 估值字段");
        nine.put("valuation", valuation);

        // 3. 行情
        Map<String, Object> quote = asMap(raw.get("quote"));
        Map<String, Object> mkt = new HashMap<>();
        mkt.put("close", parseDouble(quote == null ? null : quote.get("close")));
        mkt.put("turnover", formatPct(quote == null ? null : quote.get("turn")));
        mkt.put("volume", parseDouble(quote == null ? null : quote.get("volume")));
        if (quote != null && quote.containsKey("period_high")) {
            mkt.put("periodHigh", parseDouble(quote.get("period_high")));
            mkt.put("periodLow", parseDouble(quote.get("period_low")));
            mkt.put("periodChangePct", formatPct(quote.get("period_change_pct")));
        }
        nine.put("market", mkt);

        // 4. 基础信息
        Map<String, Object> basic = asMap(raw.get("basic"));
        nine.put("company", basic);
        Map<String, Object> industry = asMap(raw.get("industry"));
        nine.put("industry", industry);

        // 5. 业绩预告/分红
        nine.put("forecast", raw.get("forecast"));
        nine.put("dividend", raw.get("dividend"));

        // 6. 综合结论
        Map<String, Object> conclusion = new HashMap<>();
        conclusion.put("dataSource", "baostock (2026-06-12)");
        conclusion.put("method", "高景气九维");
        conclusion.put("disclaimer", "本研报为基于公开数据的事实陈述, 不构成投资建议");
        nine.put("conclusion", conclusion);

        return nine;
    }

    // ============================================================
    // 4. 财务摘要
    // ============================================================
    private Map<String, Object> buildFinancialSummary(List<Object> finHistory) {
        Map<String, Object> summary = new HashMap<>();
        if (finHistory == null || finHistory.isEmpty()) return summary;

        summary.put("periods", finHistory.size());
        List<String> periodLabels = new ArrayList<>();
        List<Double> roeList = new ArrayList<>();
        List<Double> gmList = new ArrayList<>();
        List<Double> nmList = new ArrayList<>();
        List<Double> yoyNiList = new ArrayList<>();

        for (Object o : finHistory) {
            Map<String, Object> rec = asMap(o);
            periodLabels.add(String.valueOf(rec.get("statDate")));
            Map<String, Object> p = asMap(rec.get("profitability"));
            Map<String, Object> g = asMap(rec.get("growth"));
            roeList.add(p == null ? null : parseDouble(p.get("roe_avg")));
            gmList.add(p == null ? null : parseDouble(p.get("gp_margin")));
            nmList.add(p == null ? null : parseDouble(p.get("np_margin")));
            yoyNiList.add(g == null ? null : parseDouble(g.get("yoy_ni")));
        }
        summary.put("periodLabels", periodLabels);
        summary.put("roeList", roeList);
        summary.put("grossMarginList", gmList);
        summary.put("netMarginList", nmList);
        summary.put("yoyNetProfitList", yoyNiList);
        return summary;
    }

    // ============================================================
    // 5. 催化剂/风险
    // ============================================================
    private List<String> buildCatalysts(Map<String, Object> raw, String name) {
        List<String> catalysts = new ArrayList<>();
        Object forecast = raw.get("forecast");
        if (forecast instanceof List<?> list && !list.isEmpty()) {
            catalysts.add("📢 业绩预告/快报: " + list.size() + " 条记录");
        }
        // 季度环比反转信号
        List<Object> finHistory = asList(raw.get("financial_history"));
        if (finHistory.size() >= 2) {
            Map<String, Object> latest = asMap(finHistory.get(finHistory.size() - 1));
            Map<String, Object> prev = asMap(finHistory.get(finHistory.size() - 2));
            Map<String, Object> lp = asMap(latest.get("profitability"));
            Map<String, Object> pp = asMap(prev.get("profitability"));
            Double curNm = parseDouble(lp == null ? null : lp.get("np_margin"));
            Double preNm = parseDouble(pp == null ? null : pp.get("np_margin"));
            if (curNm != null && preNm != null && curNm - preNm > 0.05) {
                catalysts.add(String.format("🔥 净利率季度环比 +%.1fpp, 业绩反转信号", (curNm - preNm) * 100));
            }
        }
        catalysts.add("🏭 关注下游Capec指引与新签订单公告");
        return catalysts;
    }

    private List<String> buildRisks(Map<String, Object> raw, String name) {
        List<String> risks = new ArrayList<>();
        List<Object> finHistory = asList(raw.get("financial_history"));
        if (!finHistory.isEmpty()) {
            Map<String, Object> latest = asMap(finHistory.get(finHistory.size() - 1));
            Map<String, Object> p = asMap(latest.get("profitability"));
            Double roe = parseDouble(p == null ? null : p.get("roe_avg"));
            Double nm = parseDouble(p == null ? null : p.get("np_margin"));
            if (roe != null && roe < 0.05) {
                risks.add(String.format("⚠️ ROE仅%.2f%%, 盈利质量弱", roe * 100));
            }
            if (nm != null && nm < 0) {
                risks.add("⚠️ 净利率为负, 经营亏损");
            }
        }
        risks.add("⚠️ 客户集中度风险: 半导体设备公司前五大客户占比通常 >60%");
        risks.add("⚠️ 应收账款周期长, 现金流压力需关注");
        risks.add("⚠️ 行业β波动大, 短期受市场情绪影响显著");
        return risks;
    }

    // ============================================================
    // 启发式推断 (基于行业名/股票名, 后续可替换为更细的映射表)
    // ============================================================
    private String inferLayer(String industry, String name) {
        if (industry.contains("半导体") || industry.contains("电子") || industry.contains("C35")) {
            if (name.contains("测") || name.contains("精")) return "第4层 - 测试设备";
            if (name.contains("蚀")) return "第3层 - 刻蚀设备";
            if (name.contains("光")) return "第3层 - 光刻/检测设备";
        }
        if (industry.contains("医药") || industry.contains("生物")) return "第3-4层 - 创新药/医疗器械";
        return "需结合个股业务定位";
    }

    private String inferChainPath(String industry, String name) {
        if (name.contains("精智达") || name.contains("华峰") || name.contains("长川")) {
            return "AI/HBM需求 → 存储原厂(三星/海力士/长江存储/长鑫) → 测试设备供应商";
        }
        if (name.contains("中微") || name.contains("北方华创")) {
            return "AI/HBM需求 → 晶圆厂 → 刻蚀/沉积设备";
        }
        return "需结合行业上下游分析";
    }

    private String inferMoatType(String industry, String name) {
        if (industry.contains("半导体") || industry.contains("C35")) {
            return "地缘保护型(出口管制+国产替代政策) + 技术壁垒(高端设备研发周期3-5年)";
        }
        return "需结合个股分析";
    }

    private String inferCompetitors(String industry, String name) {
        if (name.contains("精智达")) return "爱德万(日本) / 泰瑞达(美国) / 精智达(国内唯一)";
        if (name.contains("华峰")) return "泰瑞达(美国) / 爱德万(日本) / 华峰测控(国内领先)";
        if (name.contains("长川")) return "爱德万 / 泰瑞达 / 长川科技 / 分选机其他玩家";
        return "需结合行业研究";
    }

    private String inferChinesePosition(String industry, String name) {
        if (industry.contains("半导体") || industry.contains("C35")) {
            return "国产替代核心受益方, 但高端产品仍由外资主导";
        }
        return "需结合行业格局";
    }

    private String inferGeoAdvantage(String industry, String name) {
        if (industry.contains("半导体") || industry.contains("C35")) {
            return "美对华14nm以下设备出口管制 → 国产替代窗口期3-5年";
        }
        return "需结合地缘政治分析";
    }

    private int calcMoat(String industry, String name) {
        int score = 5;
        if (industry.contains("半导体") || industry.contains("C35")) score += 3;
        if (name.contains("精智达") || name.contains("华峰")) score += 1;
        if (name.contains("唯一") || name.contains("稀缺")) score += 1;
        return Math.min(10, score);
    }

    // ============================================================
    // 工具
    // ============================================================
    private String normalizeCode(String code) {
        if (code == null) return "";
        code = code.trim().toLowerCase();
        if (code.contains(".")) return code;
        if (code.matches("\\d{6}")) {
            if (code.startsWith("60") || code.startsWith("68") || code.startsWith("90")) return "sh." + code;
            if (code.startsWith("00") || code.startsWith("30") || code.startsWith("20")) return "sz." + code;
            if (code.startsWith("43") || code.startsWith("83") || code.startsWith("87") || code.startsWith("88")) return "bj." + code;
        }
        return code;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        if (o == null) return null;
        if (o instanceof Map) return (Map<String, Object>) o;
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(Object o) {
        if (o == null) return Collections.emptyList();
        if (o instanceof List) return (List<Object>) o;
        return Collections.emptyList();
    }

    private Double parseDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    private String formatPct(Object o) {
        Double d = parseDouble(o);
        if (d == null) return "N/A";
        return String.format("%.2f%%", d * 100);
    }
}
