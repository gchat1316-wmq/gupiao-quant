package com.quant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.StockAnalysisProperties;
import com.quant.dto.stockanalysis.StockAnalysisRecordListDTO;
import com.quant.dto.stockanalysis.StockAnalysisRequest;
import com.quant.dto.stockanalysis.StockAnalysisResponse;
import com.quant.entity.StockAnalysisRecord;
import com.quant.repository.StockAnalysisRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 个股分析服务 (异步版)
 * - submit: 创建 PENDING 记录, 立即返回 id
 * - @Async executeAsync: 后台跑 baostock + 紫苏叶/九维, 写回 DB
 * - getById / list: 查询记录
 *
 * 缓存策略: 同 code + method 的 SUCCESS 记录 1 小时内直接复用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAnalysisService {

    private final StockAnalysisProperties properties;
    private final StockAnalysisRecordRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 缓存有效期 (小时) */
    private static final int CACHE_HOURS = 1;

    // ============================================================
    // 1. 提交任务 (立即返回 recordId)
    // ============================================================
    @Transactional
    public Long submit(StockAnalysisRequest req) {
        String codeRaw = req.getCode() == null ? "" : req.getCode().trim();
        if (codeRaw.isEmpty()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        String code = normalizeCode(codeRaw);
        String method = req.getMethod() == null ? "full" : req.getMethod();
        Integer years = req.getYears() == null ? 2 : req.getYears();
        Boolean lite = req.getLite() == null ? Boolean.TRUE : req.getLite();
        Integer quoteDays = req.getQuoteDays() == null ? 60 : req.getQuoteDays();

        // 缓存命中: 1小时内同 code+method 直接复用
        Pageable one = PageRequest.of(0, 1);
        var existing = repository.findLatestSuccess(code, method, one);
        if (!existing.isEmpty()) {
            StockAnalysisRecord r = existing.getContent().get(0);
            if (r.getFinishedAt() != null
                    && r.getFinishedAt().isAfter(LocalDateTime.now().minusHours(CACHE_HOURS))) {
                log.info("缓存命中: code={} method={} recordId={}", code, method, r.getId());
                return r.getId();
            }
        }

        // 新建 PENDING 记录
        StockAnalysisRecord rec = new StockAnalysisRecord();
        rec.setStockCode(code);
        rec.setStockCodeRaw(codeRaw);
        rec.setMethod(method);
        rec.setYears(years);
        rec.setLite(lite ? 1 : 0);
        rec.setQuoteDays(quoteDays);
        rec.setStatus("PENDING");
        rec = repository.save(rec);
        log.info("提交个股分析: id={} code={} method={}", rec.getId(), code, method);
        return rec.getId();
    }

    // ============================================================
    // 2. 异步执行 (Spring 线程池)
    // ============================================================
    @Async("stockAnalysisExecutor")
    public void executeAsync(Long recordId) {
        StockAnalysisRecord rec = repository.findById(recordId).orElse(null);
        if (rec == null) {
            log.error("记录不存在: id={}", recordId);
            return;
        }
        if (!"PENDING".equals(rec.getStatus())) {
            log.warn("记录非 PENDING 状态, 跳过: id={} status={}", recordId, rec.getStatus());
            return;
        }
        // 更新为 RUNNING
        rec.setStatus("RUNNING");
        rec.setStartedAt(LocalDateTime.now());
        rec = repository.save(rec);

        long start = System.currentTimeMillis();
        try {
            StockAnalysisRequest req = new StockAnalysisRequest();
            req.setCode(rec.getStockCodeRaw());
            req.setMethod(rec.getMethod());
            req.setYears(rec.getYears());
            req.setLite(rec.getLite() == 1);
            req.setQuoteDays(rec.getQuoteDays());

            StockAnalysisResponse resp = doAnalyze(req);
            long elapsed = System.currentTimeMillis() - start;

            // 写回结果
            rec.setStatus("SUCCESS");
            rec.setFinishedAt(LocalDateTime.now());
            rec.setElapsedMs((int) elapsed);
            if (resp != null) {
                rec.setStockName(resp.getName());
                rec.setCurrentPrice(resp.getCurrentPrice() == null ? null : BigDecimal.valueOf(resp.getCurrentPrice()));
                rec.setVerdict(resp.getVerdict());
                rec.setMoatScore(resp.getMoatScore());
                rec.setResultJson(objectMapper.writeValueAsString(resp));
            }
            repository.save(rec);
            log.info("分析完成: id={} code={} elapsed={}ms", recordId, rec.getStockCode(), elapsed);
        } catch (Exception e) {
            log.error("分析失败: id={}", recordId, e);
            rec.setStatus("FAILED");
            rec.setFinishedAt(LocalDateTime.now());
            rec.setElapsedMs((int) (System.currentTimeMillis() - start));
            rec.setErrorMessage(e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            repository.save(rec);
        }
    }

    // ============================================================
    // 3. 同步版 (供 executeAsync 内部调用, 也可被外部直接调)
    // ============================================================
    public StockAnalysisResponse doAnalyze(StockAnalysisRequest req) {
        String code = normalizeCode(req.getCode());
        String method = req.getMethod() == null ? "full" : req.getMethod();

        Map<String, Object> rawData = fetchPack(code, req);
        if (rawData == null || rawData.isEmpty()) {
            throw new RuntimeException("baostock 数据获取失败");
        }

        Map<String, Object> basic = asMap(rawData.get("basic"));
        String name = basic == null ? code : String.valueOf(basic.getOrDefault("code_name", code));
        Map<String, Object> quote = asMap(rawData.get("quote"));
        Double price = quote == null ? null : parseDouble(quote.get("close"));

        Map<String, Object> financialSummary = buildFinancialSummary(asList(rawData.get("financial_history")));

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

        Map<String, Object> nineDim = null;
        if ("gaojingqi".equals(method) || "full".equals(method)) {
            nineDim = runGaoJingQi(rawData, name, price);
        }

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
                .elapsedMs(0L)
                .build();
    }

    // ============================================================
    // 4. 查询接口
    // ============================================================
    public StockAnalysisRecord getById(Long id) {
        return repository.findById(id).orElse(null);
    }

    public StockAnalysisRecord save(StockAnalysisRecord rec) {
        return repository.save(rec);
    }

    public StockAnalysisResponse parseRecordJson(StockAnalysisRecord rec) {
        if (rec == null || rec.getResultJson() == null) return null;
        try {
            return objectMapper.readValue(rec.getResultJson(), StockAnalysisResponse.class);
        } catch (Exception e) {
            log.warn("解析 result_json 失败: id={}", rec.getId(), e);
            return null;
        }
    }


    public Page<StockAnalysisRecordListDTO> list(String kw, String status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        return repository.search(kw, status, pageable).map(this::toListDTO);
    }

    public List<StockAnalysisRecordListDTO> toListDTOList(List<StockAnalysisRecord> records) {
        return records.stream().map(this::toListDTO).toList();
    }

    private StockAnalysisRecordListDTO toListDTO(StockAnalysisRecord r) {
        return StockAnalysisRecordListDTO.builder()
                .id(r.getId())
                .stockCode(r.getStockCode())
                .stockCodeRaw(r.getStockCodeRaw())
                .stockName(r.getStockName())
                .method(r.getMethod())
                .status(r.getStatus())
                .verdict(r.getVerdict())
                .moatScore(r.getMoatScore())
                .currentPrice(r.getCurrentPrice())
                .elapsedMs(r.getElapsedMs())
                .errorMessage(r.getErrorMessage())
                .submittedAt(r.getSubmittedAt())
                .startedAt(r.getStartedAt())
                .finishedAt(r.getFinishedAt())
                .build();
    }

    // ============================================================
    // 5. 调 baostock (从原 service 搬过来)
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
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) stdout.append(line);
            }
            boolean done = process.waitFor(properties.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                throw new RuntimeException("baostock 调用超时 (" + properties.getTimeoutSeconds() + "s)");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("baostock 退出码 " + process.exitValue() + ": " + stdout);
            }
            String content = stdout.toString();
            int idx = content.indexOf('{');
            if (idx < 0) throw new RuntimeException("baostock 输出无 JSON");
            return objectMapper.readValue(content.substring(idx), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("baostock 调用失败: " + e.getMessage(), e);
        }
    }

    // ============================================================
    // 6. 紫苏叶 + 高景气九维 (从原 service 搬过来, 略)
    // ============================================================
    private Map<String, Object> runPurplePerilla(Map<String, Object> raw, String name) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> industry = asMap(raw.get("industry"));
        String industryName = industry == null ? "未知" : String.valueOf(industry.getOrDefault("industry", "未知"));
        Map<String, Object> chain = new HashMap<>();
        chain.put("industry", industryName);
        chain.put("name", name);
        chain.put("layer", inferLayer(industryName, name));
        chain.put("chainPath", inferChainPath(industryName, name));
        chain.put("moatType", inferMoatType(industryName, name));
        result.put("chainPosition", chain);
        Map<String, Object> comp = new HashMap<>();
        comp.put("globalPlayers", inferCompetitors(industryName, name));
        comp.put("chinesePosition", inferChinesePosition(industryName, name));
        comp.put("geographicAdvantage", inferGeoAdvantage(industryName, name));
        result.put("competition", comp);
        Map<String, Object> q = new HashMap<>();
        q.put("Q1_irreplaceable", "需要核实 - 该环节全球供应商数量与对标分析");
        q.put("Q2_competitorCount", "需要核实 - 国内/全球具体玩家数");
        q.put("Q3_demandTrend", "需要核实 - 下游Capex订单趋势");
        q.put("note", "本数据为占位提示, 需结合个股非结构化调研");
        result.put("threeQuestions", q);
        int moat = calcMoat(industryName, name);
        result.put("moatScore", moat);
        String verdict;
        if (moat >= 8) verdict = "盯住/就是它了";
        else if (moat >= 6) verdict = "盯住";
        else if (moat >= 4) verdict = "观望";
        else verdict = "回避";
        result.put("verdict", verdict);
        return result;
    }

    private Map<String, Object> runGaoJingQi(Map<String, Object> raw, String name, Double price) {
        Map<String, Object> nine = new HashMap<>();
        List<Object> finHistory = asList(raw.get("financial_history"));
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
        Map<String, Object> valuation = new HashMap<>();
        valuation.put("currentPrice", price);
        valuation.put("peTtm", "N/A (需用 eastmoney / Wind)");
        valuation.put("note", "Baostock 不提供 PE/PB/PS 估值字段");
        nine.put("valuation", valuation);
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
        nine.put("company", asMap(raw.get("basic")));
        nine.put("industry", asMap(raw.get("industry")));
        nine.put("forecast", raw.get("forecast"));
        nine.put("dividend", raw.get("dividend"));
        return nine;
    }

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

    private List<String> buildCatalysts(Map<String, Object> raw, String name) {
        List<String> catalysts = new ArrayList<>();
        Object forecast = raw.get("forecast");
        if (forecast instanceof List<?> list && !list.isEmpty()) {
            catalysts.add("📢 业绩预告/快报: " + list.size() + " 条记录");
        }
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
            if (roe != null && roe < 0.05) risks.add(String.format("⚠️ ROE仅%.2f%%, 盈利质量弱", roe * 100));
            if (nm != null && nm < 0) risks.add("⚠️ 净利率为负, 经营亏损");
        }
        risks.add("⚠️ 客户集中度风险: 半导体设备公司前五大客户占比通常 >60%");
        risks.add("⚠️ 应收账款周期长, 现金流压力需关注");
        risks.add("⚠️ 行业β波动大, 短期受市场情绪影响显著");
        return risks;
    }

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
