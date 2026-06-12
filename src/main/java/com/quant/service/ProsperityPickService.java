package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quant.config.AiProperties;
import com.quant.config.StockAnalysisProperties;
import com.quant.dto.invest.ProsperityPickRecentDTO;
import com.quant.dto.invest.ProsperityPickResultDTO;
import com.quant.entity.InvestProsperityPick;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.InvestProsperityPickRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;
import com.quant.service.search.WebSearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProsperityPickService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FINANCIAL_QUARTERS = 12;
    private static final int RECENT_HISTORY_DAYS = 3;

    private final StockQueryService stockQueryService;
    private final TradeStockFinancialRepository financialRepo;
    private final TradeStockDailyRepository dailyRepo;
    private final InvestProsperityPickRepository repo;
    private final MiniMaxClient miniMaxClient;
    private final SenseNovaClient senseNovaClient;
    private final WebSearchClient webSearchClient;
    private final AiProperties aiProperties;
    private final StockAnalysisProperties stockAnalysisProperties;

    public ProsperityPickResultDTO analyze(String keyword, boolean force) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("请输入股票名称或代码");
        }
        TradeStockBasic basic = stockQueryService.resolveStock(keyword.trim())
                .orElseThrow(() -> new IllegalArgumentException("未找到股票: " + keyword));

        LocalDate today = LocalDate.now();
        if (!force) {
            Optional<InvestProsperityPick> cached = repo.findByStockCodeAndAnalysisDate(
                    basic.getStockCode(), today);
            if (cached.isPresent()) {
                InvestProsperityPick cachedEntity = cached.get();
                if (cachedEntity.getDegraded() == null || cachedEntity.getDegraded() != 1) {
                    log.info("命中缓存: {} {}", basic.getStockCode(), today);
                    return toResultDTO(cachedEntity, basic, true);
                }
                log.info("命中演示数据缓存，重新分析: {} {}", basic.getStockCode(), today);
            }
        }

        long startMs = System.currentTimeMillis();

        // ① 构建基础 Profile
        ProsperityPickResultDTO.Profile profile = buildProfile(basic);

        // ② 抓取 baostock 真实数据（可选，失败不阻断主流程）
        Map<String, Object> baostockData = null;
        ProsperityPickResultDTO.FinancialSummary financialSummary = null;
        try {
            baostockData = fetchBaostockPack(basic.getStockCode());
            if (baostockData != null && !baostockData.isEmpty()) {
                financialSummary = buildFinancialSummaryFromBaostock(baostockData);
                log.info("baostock 数据获取成功: {}", basic.getStockCode());
            }
        } catch (Exception e) {
            log.warn("baostock 数据获取失败，继续 AI 分析: {}", e.getMessage());
        }

        // ③ 紫苏叶 + 九维框架分析（基于 baostock 数据）
        JsonNode chainPosition = null;
        JsonNode nineDimension = null;
        Integer moatScore = null;
        String verdict = null;
        List<String> catalysts = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        if (baostockData != null && !baostockData.isEmpty()) {
            try {
                Map<String, Object> purplePerilla = runPurplePerilla(baostockData, basic.getStockName());
                chainPosition = toJsonNode(purplePerilla.get("chainPosition"));
                moatScore = (Integer) purplePerilla.get("moatScore");
                verdict = (String) purplePerilla.get("verdict");

                Map<String, Object> gaoJingQi = runGaoJingQi(baostockData, basic.getStockName(), profile.getCurrentPrice());
                nineDimension = toJsonNode(gaoJingQi);

                catalysts = buildCatalysts(baostockData, basic.getStockName());
                risks = buildRisks(baostockData, basic.getStockName());
                log.info("紫苏叶+九维分析完成: moatScore={}, verdict={}", moatScore, verdict);
            } catch (Exception e) {
                log.warn("紫苏叶/九维分析失败，继续 AI 分析: {}", e.getMessage());
            }
        }

        // ④ AI 六维深度分析（行业/公司/估值/技术/资金/总结）
        String prompt = buildPrompt(profile, basic, baostockData);
        String aiJson;
        try {
            aiJson = analyzeWithAi(prompt);
        } catch (Exception e) {
            log.warn("AI 调用失败: {}", e.getMessage());
            throw new IllegalStateException("AI 调用失败: " + e.getMessage(), e);
        }

        int elapsedMs = (int) (System.currentTimeMillis() - startMs);

        // ⑤ 保存
        InvestProsperityPick entity = repo.findByStockCodeAndAnalysisDate(basic.getStockCode(), today)
                .orElseGet(InvestProsperityPick::new);
        entity.setStockCode(basic.getStockCode());
        entity.setStockName(basic.getStockName() != null ? basic.getStockName() : basic.getStockCode());
        entity.setAnalysisDate(today);
        entity.setResultJson(aiJson);
        entity.setDegraded(0);
        entity.setMoatScore(moatScore);
        entity.setVerdict(verdict);
        entity.setElapsedMs(elapsedMs);
        if (chainPosition != null) entity.setChainPosition(chainPosition.toString());
        if (nineDimension != null) entity.setNineDimension(nineDimension.toString());
        if (baostockData != null) {
            try { entity.setBaostockData(MAPPER.writeValueAsString(baostockData)); }
            catch (Exception e) { log.warn("序列化 baostock 数据失败", e); }
        }
        if (force) {
            entity.setImageUrl(null);
            entity.setImagePrompt(null);
        }
        InvestProsperityPick saved = repo.save(entity);
        log.info("景气度选股分析完成: {} elapsed={}ms", basic.getStockCode(), elapsedMs);

        // ⑥ 生成报告详情 HTML（异步不阻塞返回）
        try {
            String reportHtml = buildReportHtml(saved, basic);
            saved.setReportHtml(reportHtml);
            repo.save(saved);
        } catch (Exception e) {
            log.warn("报告详情 HTML 生成失败，不影响主流程: {}", e.getMessage());
        }

        return toResultDTO(saved, basic, false)
                .toBuilder()
                .chainPosition(chainPosition)
                .nineDimension(nineDimension)
                .financialSummary(financialSummary)
                .moatScore(moatScore)
                .verdict(verdict)
                .catalysts(catalysts)
                .risks(risks)
                .elapsedMs(elapsedMs)
                .reportHtml(saved.getReportHtml())
                .build();
    }

    // ================================================================
    // baostock 数据获取（复用个股分析的 Python 脚本）
    // ================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchBaostockPack(String stockCode) {
        if (!stockAnalysisProperties.isEnabled()) {
            log.info("stock-analysis 模块未启用，跳过 baostock");
            return null;
        }
        String code = normalizeCode(stockCode);
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    stockAnalysisProperties.getPythonCommand(),
                    stockAnalysisProperties.getPythonScript(),
                    "pack", code,
                    String.valueOf(60),
                    String.valueOf(2),
                    "--lite"
            ));
            log.info("调 baostock: {}", String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder stdout = new StringBuilder();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) stdout.append(line);
            }
            boolean done = process.waitFor(stockAnalysisProperties.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                throw new RuntimeException("baostock 调用超限 (" + stockAnalysisProperties.getTimeoutSeconds() + "s)");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("baostock 退出码 " + process.exitValue());
            }
            String content = stdout.toString();
            int idx = content.indexOf('{');
            if (idx < 0) throw new RuntimeException("baostock 输出无 JSON");
            return MAPPER.readValue(content.substring(idx), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("baostock 调用失败: " + e.getMessage(), e);
        }
    }

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

    // ================================================================
    // 紫苏叶 + 九维（从 StockAnalysisService 搬过来的逻辑）
    // ================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> runPurplePerilla(Map<String, Object> raw, String name) {
        Map<String, Object> result = new java.util.HashMap<>();
        Map<String, Object> industry = asMap(raw.get("industry"));
        String industryName = industry == null ? "未知" : String.valueOf(industry.getOrDefault("industry", "未知"));

        Map<String, Object> chain = new java.util.HashMap<>();
        chain.put("industry", industryName);
        chain.put("name", name);
        chain.put("layer", inferLayer(industryName, name));
        chain.put("chainPath", inferChainPath(industryName, name));
        chain.put("moatType", inferMoatType(industryName, name));
        result.put("chainPosition", chain);

        Map<String, Object> comp = new java.util.HashMap<>();
        comp.put("globalPlayers", inferCompetitors(industryName, name));
        comp.put("chinesePosition", inferChinesePosition(industryName, name));
        comp.put("geographicAdvantage", inferGeoAdvantage(industryName, name));
        result.put("competition", comp);

        Map<String, Object> q = new java.util.HashMap<>();
        q.put("Q1_irreplaceable", "需要核实 - 该环节全球供应商数量与对标分析");
        q.put("Q2_competitorCount", "需要核实 - 国内/全球具体玩家数");
        q.put("Q3_demandTrend", "需要核实 - 下游Capex订单趋势");
        result.put("threeQuestions", q);

        int moat = calcMoat(industryName, name);
        result.put("moatScore", moat);
        String v;
        if (moat >= 8) v = "盯住/就是它了";
        else if (moat >= 6) v = "盯住";
        else if (moat >= 4) v = "观望";
        else v = "回避";
        result.put("verdict", v);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runGaoJingQi(Map<String, Object> raw, String name, BigDecimal price) {
        Map<String, Object> nine = new java.util.HashMap<>();
        List<Object> finHistory = asList(raw.get("financial_history"));
        Map<String, Object> fin = new java.util.HashMap<>();
        if (finHistory != null && !finHistory.isEmpty()) {
            Map<String, Object> latest = asMap(finHistory.get(finHistory.size() - 1));
            Map<String, Object> prof = asMap(latest.get("profitability"));
            Map<String, Object> growth = asMap(latest.get("growth"));
            fin.put("latestPeriod", latest.get("statDate"));
            fin.put("roe", formatPct(prof == null ? null : prof.get("roe_avg")));
            fin.put("grossMargin", formatPct(prof == null ? null : prof.get("gp_margin")));
            fin.put("netMargin", formatPct(prof == null ? null : prof.get("np_margin")));
            fin.put("yoyNetProfit", formatPct(growth == null ? null : growth.get("yoy_ni")));
        }
        nine.put("financial", fin);

        Map<String, Object> valuation = new java.util.HashMap<>();
        valuation.put("currentPrice", price);
        nine.put("valuation", valuation);

        Map<String, Object> quote = asMap(raw.get("quote"));
        Map<String, Object> mkt = new java.util.HashMap<>();
        if (quote != null) {
            mkt.put("close", parseDouble(quote.get("close")));
            mkt.put("turnover", formatPct(quote.get("turn")));
            mkt.put("volume", parseDouble(quote.get("volume")));
            if (quote.containsKey("period_high")) {
                mkt.put("periodHigh", parseDouble(quote.get("period_high")));
                mkt.put("periodLow", parseDouble(quote.get("period_low")));
                mkt.put("periodChangePct", formatPct(quote.get("period_change_pct")));
            }
        }
        nine.put("market", mkt);
        nine.put("company", asMap(raw.get("basic")));
        nine.put("industry", asMap(raw.get("industry")));
        return nine;
    }

    @SuppressWarnings("unchecked")
    private ProsperityPickResultDTO.FinancialSummary buildFinancialSummaryFromBaostock(Map<String, Object> baostockData) {
        List<Object> finHistory = asList(baostockData.get("financial_history"));
        if (finHistory == null || finHistory.isEmpty()) return null;
        List<String> periodLabels = new ArrayList<>();
        List<Double> roeList = new ArrayList<>();
        List<Double> gmList = new ArrayList<>();
        List<Double> nmList = new ArrayList<>();
        List<Double> yoyNiList = new ArrayList<>();
        for (Object o : finHistory) {
            Map<String, Object> rec = asMap(o);
            if (rec == null) continue;
            periodLabels.add(String.valueOf(rec.get("statDate")));
            Map<String, Object> p = asMap(rec.get("profitability"));
            Map<String, Object> g = asMap(rec.get("growth"));
            roeList.add(p == null ? null : parseDouble(p.get("roe_avg")));
            gmList.add(p == null ? null : parseDouble(p.get("gp_margin")));
            nmList.add(p == null ? null : parseDouble(p.get("np_margin")));
            yoyNiList.add(g == null ? null : parseDouble(g.get("yoy_ni")));
        }
        return ProsperityPickResultDTO.FinancialSummary.builder()
                .periodLabels(periodLabels)
                .roeList(roeList)
                .grossMarginList(gmList)
                .netMarginList(nmList)
                .yoyNetProfitList(yoyNiList)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> buildCatalysts(Map<String, Object> raw, String name) {
        List<String> catalysts = new ArrayList<>();
        Object forecast = raw.get("forecast");
        if (forecast instanceof List<?> list && !list.isEmpty()) {
            catalysts.add("业绩预告/快报: " + list.size() + " 条记录");
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
                catalysts.add(String.format("净利率季度环比 +%.1fpp, 业绩反转信号", (curNm - preNm) * 100));
            }
        }
        return catalysts;
    }

    @SuppressWarnings("unchecked")
    private List<String> buildRisks(Map<String, Object> raw, String name) {
        List<String> risks = new ArrayList<>();
        List<Object> finHistory = asList(raw.get("financial_history"));
        if (!finHistory.isEmpty()) {
            Map<String, Object> latest = asMap(finHistory.get(finHistory.size() - 1));
            Map<String, Object> p = asMap(latest.get("profitability"));
            Double roe = parseDouble(p == null ? null : p.get("roe_avg"));
            Double nm = parseDouble(p == null ? null : p.get("np_margin"));
            if (roe != null && roe < 0.05) risks.add(String.format("ROE仅%.2f%%, 盈利质量弱", roe * 100));
            if (nm != null && nm < 0) risks.add("净利率为负, 经营亏损");
        }
        return risks;
    }

    // ======== 紫苏叶推断逻辑（复用 StockAnalysisService 的规则） ========

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

    // ================================================================
    // AI 分析（六维研报）
    // ================================================================

    private String analyzeWithAi(String prompt) {
        Exception miniMaxError = null;
        try {
            return normalizeAiJson(miniMaxClient.chatComplete(SYSTEM_PROMPT, prompt));
        } catch (Exception e) {
            miniMaxError = e;
            log.warn("MiniMax 分析失败，尝试 SenseNova: {}", e.getMessage());
        }

        try {
            return normalizeAiJson(senseNovaClient.chatComplete(SYSTEM_PROMPT, prompt));
        } catch (Exception senseNovaError) {
            String message = "MiniMax: " + miniMaxError.getMessage()
                    + "; SenseNova: " + senseNovaError.getMessage();
            throw new IllegalStateException(message, senseNovaError);
        }
    }

    private String normalizeAiJson(String raw) {
        String aiJson = extractJson(raw);
        try {
            MAPPER.readTree(aiJson);
        } catch (Exception e) {
            throw new IllegalStateException("AI 返回不是合法 JSON: " + e.getMessage(), e);
        }
        return aiJson;
    }

    // ================================================================
    // 信息图
    // ================================================================

    public String generateInfographic(Long id) {
        InvestProsperityPick entity = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分析记录不存在: " + id));
        if (entity.getImageUrl() != null && !entity.getImageUrl().isBlank()) {
            return entity.getImageUrl();
        }

        String prompt = entity.getImagePrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = buildImagePromptFromResult(entity);
        }

        String imageUrl;
        try {
            imageUrl = senseNovaClient.generateImage(prompt);
        } catch (Exception e) {
            log.warn("信息图生成失败: {}", e.getMessage());
            throw new IllegalStateException("信息图生成失败: " + e.getMessage(), e);
        }
        entity.setImageUrl(imageUrl);
        entity.setImagePrompt(prompt);
        repo.save(entity);
        return imageUrl;
    }

    // ================================================================
    // 列表 / 查询
    // ================================================================

    public List<ProsperityPickRecentDTO> recent() {
        LocalDate cutoff = LocalDate.now().minusDays(RECENT_HISTORY_DAYS - 1L);
        return repo.findTop30ByAnalysisDateGreaterThanEqualOrderByAnalysisDateDescIdDesc(cutoff).stream()
                .filter(e -> e.getDegraded() == null || e.getDegraded() != 1)
                .map(this::toRecentDTO)
                .collect(Collectors.toList());
    }

    private ProsperityPickRecentDTO toRecentDTO(InvestProsperityPick entity) {
        JsonNode root = readAnalysis(entity.getResultJson());
        JsonNode summary = root.path("summary");
        List<String> bullets = new ArrayList<>();
        JsonNode bulletNode = summary.path("bullets");
        if (bulletNode.isArray()) {
            for (JsonNode node : bulletNode) {
                String text = node.asText("");
                if (!text.isBlank()) bullets.add(text);
                if (bullets.size() >= 3) break;
            }
        }
        // 从 profile 获取 currentPrice
        BigDecimal currentPrice = null;
        try {
            JsonNode profileNode = root.path("profile");
            if (profileNode.has("currentPrice")) {
                currentPrice = profileNode.get("currentPrice").decimalValue();
            }
        } catch (Exception ignore) {}

        return ProsperityPickRecentDTO.builder()
                .id(entity.getId())
                .stockCode(entity.getStockCode())
                .stockName(entity.getStockName())
                .analysisDate(entity.getAnalysisDate())
                .imageUrl(entity.getImageUrl())
                .summaryOneLiner(summary.path("oneLiner").asText(""))
                .summaryBullets(bullets)
                .valuationVerdict(root.path("valuation").path("verdict").asText(""))
                .technicalVerdict(root.path("technical").path("verdict").asText(""))
                .capitalVerdict(root.path("capital").path("verdict").asText(""))
                .degraded(entity.getDegraded() != null && entity.getDegraded() == 1)
                .moatScore(entity.getMoatScore())
                .verdict(entity.getVerdict())
                .hasReport(entity.getReportHtml() != null && !entity.getReportHtml().isBlank())
                .currentPrice(currentPrice)
                .build();
    }

    public ProsperityPickResultDTO get(Long id) {
        InvestProsperityPick entity = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分析记录不存在: " + id));
        TradeStockBasic basic = stockQueryService.resolveStock(entity.getStockCode())
                .orElseGet(() -> {
                    TradeStockBasic b = new TradeStockBasic();
                    b.setStockCode(entity.getStockCode());
                    b.setStockName(entity.getStockName());
                    return b;
                });
        return toResultDTO(entity, basic, true);
    }

    /** 获取报告详情 HTML */
    public String getReportHtml(Long id) {
        InvestProsperityPick entity = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分析记录不存在: " + id));
        if (entity.getReportHtml() != null && !entity.getReportHtml().isBlank()) {
            return entity.getReportHtml();
        }
        // 懒生成
        TradeStockBasic basic = stockQueryService.resolveStock(entity.getStockCode())
                .orElseGet(() -> {
                    TradeStockBasic b = new TradeStockBasic();
                    b.setStockCode(entity.getStockCode());
                    b.setStockName(entity.getStockName());
                    return b;
                });
        String html = buildReportHtml(entity, basic);
        entity.setReportHtml(html);
        repo.save(entity);
        return html;
    }

    // ================================================================
    // 内部工具
    // ================================================================

    private ProsperityPickResultDTO.Profile buildProfile(TradeStockBasic basic) {
        ProsperityPickResultDTO.Profile.ProfileBuilder pb = ProsperityPickResultDTO.Profile.builder()
                .stockCode(basic.getStockCode())
                .stockName(basic.getStockName())
                .exchange(basic.getExchange())
                .board(StockQueryService.deriveBoard(basic.getStockCode()))
                .industry(basic.getSectorNames())
                .peTtm(basic.getPeTtm())
                .pb(basic.getPb())
                .psTtm(basic.getPsTtm());

        Optional<TradeStockDaily> dailyOpt = dailyRepo.findFirstByStockCodeOrderByTradeDateDesc(basic.getStockCode());
        dailyOpt.ifPresent(d -> {
            pb.currentPrice(d.getClosePrice());
            if (d.getClosePrice() != null && basic.getTotalShares() != null) {
                BigDecimal cap = d.getClosePrice()
                        .multiply(BigDecimal.valueOf(basic.getTotalShares()))
                        .divide(BigDecimal.valueOf(100_000_000L), 2, RoundingMode.HALF_UP);
                pb.totalMarketCap(cap);
            }
        });

        List<TradeStockFinancial> fin = financialRepo
                .findByStockCodeOrderByReportDateDesc(basic.getStockCode());
        if (!fin.isEmpty()) {
            TradeStockFinancial latest = fin.get(0);
            pb.latestReportDate(latest.getReportDate() != null ? latest.getReportDate().toString() : null);
            pb.latestRevenue(formatYi(latest.getRevenue()));
            pb.latestNetProfit(formatYi(latest.getNetProfit()));
        }
        return pb.build();
    }

    private String formatYi(BigDecimal raw) {
        if (raw == null) return null;
        BigDecimal yi = raw.divide(BigDecimal.valueOf(100_000_000L), 2, RoundingMode.HALF_UP);
        return yi + " 亿";
    }

    private String buildPrompt(ProsperityPickResultDTO.Profile profile, TradeStockBasic basic,
                               Map<String, Object> baostockData) {
        StringBuilder sb = new StringBuilder();
        sb.append("分析日期: ").append(LocalDate.now()).append("\n");
        sb.append("公司: ").append(profile.getStockName()).append(" ").append(profile.getStockCode()).append(" (A股)\n");
        if (profile.getCurrentPrice() != null) sb.append("现价: ").append(profile.getCurrentPrice()).append(" 元\n");
        if (profile.getTotalMarketCap() != null) sb.append("总市值: ").append(profile.getTotalMarketCap()).append(" 亿元\n");
        if (profile.getIndustry() != null) sb.append("所属行业: ").append(profile.getIndustry()).append("\n");
        if (profile.getPeTtm() != null) sb.append("PE-TTM: ").append(profile.getPeTtm()).append("\n");
        if (profile.getPb() != null) sb.append("PB: ").append(profile.getPb()).append("\n");
        if (profile.getPsTtm() != null) sb.append("PS-TTM: ").append(profile.getPsTtm()).append("\n");

        // DB 12 季度财务
        List<TradeStockFinancial> records = financialRepo
                .findByStockCodeOrderByReportDateDesc(basic.getStockCode())
                .stream().limit(FINANCIAL_QUARTERS).collect(Collectors.toList());
        if (!records.isEmpty()) {
            sb.append("\n最近 ").append(records.size()).append(" 季度财务（单位：元）：\n");
            sb.append("报告期 | 营收 | 净利润 | 扣非净利润同比 | 毛利率 | 净利率 | ROE\n");
            for (TradeStockFinancial f : records) {
                sb.append(f.getReportDate())
                        .append(" | ").append(safe(f.getRevenue()))
                        .append(" | ").append(safe(f.getNetProfit()))
                        .append(" | ").append(safe(f.getDeductedNetProfitYoy()))
                        .append(" | ").append(safe(f.getGrossMargin()))
                        .append(" | ").append(safe(f.getNetMargin()))
                        .append(" | ").append(safe(f.getRoe()))
                        .append("\n");
            }
        }

        // baostock 真实行情数据（如果有）
        if (baostockData != null && !baostockData.isEmpty()) {
            Map<String, Object> quote = asMap(baostockData.get("quote"));
            if (quote != null) {
                sb.append("\nbaostock 行情数据:\n");
                sb.append("收盘: ").append(safe(quote.get("close"))).append("\n");
                sb.append("成交量: ").append(safe(quote.get("volume"))).append("\n");
                sb.append("换手率: ").append(safe(quote.get("turn"))).append("\n");
                if (quote.containsKey("period_high")) {
                    sb.append("区间最高: ").append(safe(quote.get("period_high"))).append("\n");
                    sb.append("区间最低: ").append(safe(quote.get("period_low"))).append("\n");
                    sb.append("区间涨跌幅: ").append(safe(quote.get("period_change_pct"))).append("\n");
                }
            }
            // baostock 财务历史
            List<Object> finHistory = asList(baostockData.get("financial_history"));
            if (finHistory != null && !finHistory.isEmpty()) {
                sb.append("\nbaostock 财务历史 (近 ").append(finHistory.size()).append(" 季度):\n");
                sb.append("报告期 | ROE | 毛利率 | 净利率 | 净利YoY\n");
                for (Object o : finHistory) {
                    Map<String, Object> rec = asMap(o);
                    if (rec == null) continue;
                    Map<String, Object> p = asMap(rec.get("profitability"));
                    Map<String, Object> g = asMap(rec.get("growth"));
                    sb.append(safe(rec.get("statDate")))
                            .append(" | ").append(safe(p == null ? null : p.get("roe_avg")))
                            .append(" | ").append(safe(p == null ? null : p.get("gp_margin")))
                            .append(" | ").append(safe(p == null ? null : p.get("np_margin")))
                            .append(" | ").append(safe(g == null ? null : g.get("yoy_ni")))
                            .append("\n");
                }
            }
        }

        // 联网检索摘要
        if (webSearchClient.isEnabled()) {
            sb.append("\n联网检索摘要:\n");
            appendSearch(sb, profile.getStockName() + " 公司主营业务 董事长 介绍");
            appendSearch(sb, profile.getStockName() + " 所在行业 周期 景气度 2026");
            appendSearch(sb, profile.getStockName() + " 行业政策 十五五 全球");
            appendSearch(sb, profile.getStockName() + " 主力资金 北向资金 龙虎榜");
        } else {
            sb.append("\n（未启用联网检索，请仅基于已知信息和模型自身知识进行分析）\n");
        }

        sb.append("\n请严格按照下方 JSON 格式输出，不要输出任何额外文字、不要使用 markdown：\n");
        sb.append(JSON_SCHEMA);
        return sb.toString();
    }

    private void appendSearch(StringBuilder sb, String query) {
        List<WebSearchClient.SearchResult> rs = webSearchClient.search(query);
        if (rs.isEmpty()) return;
        sb.append("【").append(query).append("】\n");
        for (WebSearchClient.SearchResult r : rs) {
            sb.append(r.toLine()).append("\n");
        }
    }

    private String safe(Object v) {
        return v == null ? "" : v.toString();
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) s = s.substring(firstNewline + 1);
            int lastFence = s.lastIndexOf("```");
            if (lastFence > 0) s = s.substring(0, lastFence);
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    private ProsperityPickResultDTO toResultDTO(InvestProsperityPick entity, TradeStockBasic basic, boolean cached) {
        JsonNode analysis = readAnalysis(entity.getResultJson());
        JsonNode chainPositionNode = readAnalysis(entity.getChainPosition());
        JsonNode nineDimensionNode = readAnalysis(entity.getNineDimension());
        ProsperityPickResultDTO.FinancialSummary finSummary = null;

        // 从 baostock 数据重建财务趋势
        if (entity.getBaostockData() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> baostock = MAPPER.readValue(entity.getBaostockData(), Map.class);
                finSummary = buildFinancialSummaryFromBaostock(baostock);
            } catch (Exception e) {
                log.warn("解析 baostock 数据失败", e);
            }
        }

        // 催化剂/风险
        List<String> catalysts = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        if (entity.getBaostockData() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> baostock = MAPPER.readValue(entity.getBaostockData(), Map.class);
                catalysts = buildCatalysts(baostock, entity.getStockName());
                risks = buildRisks(baostock, entity.getStockName());
            } catch (Exception e) {
                log.warn("解析催化剂/风险失败", e);
            }
        }

        return ProsperityPickResultDTO.builder()
                .id(entity.getId())
                .stockCode(entity.getStockCode())
                .stockName(entity.getStockName())
                .analysisDate(entity.getAnalysisDate())
                .profile(buildProfile(basic))
                .analysis(analysis)
                .imageUrl(entity.getImageUrl())
                .degraded(entity.getDegraded() != null && entity.getDegraded() == 1)
                .cached(cached)
                .chainPosition(chainPositionNode)
                .nineDimension(nineDimensionNode)
                .financialSummary(finSummary)
                .moatScore(entity.getMoatScore())
                .verdict(entity.getVerdict())
                .catalysts(catalysts)
                .risks(risks)
                .reportHtml(entity.getReportHtml())
                .elapsedMs(entity.getElapsedMs())
                .build();
    }

    private JsonNode readAnalysis(String resultJson) {
        try {
            return MAPPER.readTree(resultJson == null || resultJson.isBlank() ? "{}" : resultJson);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private JsonNode toJsonNode(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.readTree(MAPPER.writeValueAsString(obj));
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================
    // 报告详情 HTML 生成
    // ================================================================

    private String buildReportHtml(InvestProsperityPick entity, TradeStockBasic basic) {
        JsonNode analysis = readAnalysis(entity.getResultJson());
        JsonNode chainNode = readAnalysis(entity.getChainPosition());
        JsonNode nineNode = readAnalysis(entity.getNineDimension());
        ProsperityPickResultDTO.Profile profile = buildProfile(basic);

        StringBuilder sb = new StringBuilder(16384);
        sb.append("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'><title>")
          .append(esc(entity.getStockName())).append(" 全维度分析报告</title>")
          .append("<style>")
          .append("""
              @page { size: A4; margin: 18mm 15mm; }
              * { box-sizing: border-box; }
              body { font-family: "Microsoft YaHei","PingFang SC",sans-serif; color: #1a2233; font-size: 11pt; line-height: 1.6; margin: 0; }
              h1 { font-size: 22pt; color: #0f9d58; margin: 0 0 6pt; border-bottom: 3px solid #0f9d58; padding-bottom: 6pt; }
              .sub { color: #6b7280; font-size: 10pt; margin-bottom: 12pt; }
              h2 { font-size: 14pt; color: #064e3b; margin: 14pt 0 6pt; border-left: 4px solid #0f9d58; padding-left: 8pt; }
              h3 { font-size: 12pt; color: #1a2233; margin: 10pt 0 4pt; }
              .meta-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6pt; margin: 6pt 0; }
              .meta-item { background: #f0fdf4; padding: 6pt 8pt; border-radius: 4pt; }
              .meta-label { font-size: 8pt; color: #6b7280; }
              .meta-value { font-size: 12pt; font-weight: 600; margin-top: 2pt; }
              .card { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6pt; padding: 8pt 10pt; margin: 4pt 0; }
              .card-label { font-size: 9pt; color: #6b7280; margin-bottom: 3pt; }
              .card-value { font-size: 10.5pt; color: #1f2937; line-height: 1.6; white-space: pre-wrap; }
              .section { margin: 10pt 0; }
              .cards-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 6pt; }
              table { width: 100%; border-collapse: collapse; margin: 6pt 0; font-size: 10pt; }
              th, td { border: 1px solid #d0d7e2; padding: 4pt 6pt; text-align: left; }
              th { background: #ecfdf5; font-weight: 600; color: #064e3b; }
              .verdict-cheap { color: #16a34a; font-weight: 600; }
              .verdict-fair { color: #2563eb; font-weight: 600; }
              .verdict-expensive { color: #f59e0b; font-weight: 600; }
              .verdict-bubble { color: #ef4444; font-weight: 600; }
              .badge { display: inline-block; padding: 2pt 8pt; border-radius: 10pt; font-size: 9pt; font-weight: 600; }
              .badge-green { background: #dcfce7; color: #166534; }
              .badge-yellow { background: #fef3c7; color: #92400e; }
              .badge-red { background: #fef2f2; color: #991b1b; }
              .footer { margin-top: 20pt; padding-top: 6pt; border-top: 1px solid #d0d7e2; color: #9aa4b2; font-size: 8pt; text-align: center; }
              """)
          .append("</style></head><body>");

        // 标题
        sb.append("<h1>").append(esc(entity.getStockName())).append(" (").append(esc(entity.getStockCode()))
          .append(") 全维度分析报告</h1>");
        sb.append("<div class='sub'>景气度选股 · AI六维研报 + 紫苏叶产业链 + 高景气九维 · 数据源: baostock/DB/联网检索 · 生成时间: ")
          .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
          .append("</div>");

        // 总览
        sb.append("<h2>总览</h2>");
        sb.append("<div class='meta-grid'>");
        sb.append(metaItem("现价", profile.getCurrentPrice() == null ? "-" : profile.getCurrentPrice() + " 元"));
        sb.append(metaItem("总市值", profile.getTotalMarketCap() == null ? "-" : profile.getTotalMarketCap() + " 亿"));
        sb.append(metaItem("护城河", entity.getMoatScore() == null ? "-" : entity.getMoatScore() + "/10"));
        sb.append(metaItem("紫苏叶判定", entity.getVerdict() == null ? "-" : esc(entity.getVerdict())));
        sb.append(metaItem("PE-TTM", profile.getPeTtm() == null ? "-" : profile.getPeTtm().toString()));
        sb.append(metaItem("PB", profile.getPb() == null ? "-" : profile.getPb().toString()));
        sb.append(metaItem("行业", profile.getIndustry() == null ? "-" : esc(profile.getIndustry())));
        sb.append(metaItem("耗时", entity.getElapsedMs() == null ? "-" : entity.getElapsedMs() + " ms"));
        sb.append("</div>");

        // ① 行业
        sb.append("<h2>① 行业层面</h2>");
        JsonNode ind = analysis.path("industry");
        sb.append(cardGrid(List.of(
                card("周期位置", ind.path("cyclePosition").asText("")),
                card("上轮周期复盘", ind.path("lastCycleReview").asText("")),
                card("12个月拐点预判", ind.path("next12mForecast").asText("")),
                card("进入壁垒", ind.path("entryBarrier").asText("")),
                card("行业生命周期", ind.path("lifeStage").asText("")),
                card("竞争格局", ind.path("competition").asText("")),
                card("全球共振", ind.path("globalResonance").asText(""))
        )));

        // ② 公司
        sb.append("<h2>② 公司层面</h2>");
        JsonNode comp = analysis.path("company");
        sb.append(cardGrid(List.of(
                card("业务结构", comp.path("businessMix").asText("")),
                card("12季度业绩", comp.path("quarterly12").asText("")),
                card("未来2年驱动", comp.path("next2yDriver").asText("")),
                card("护城河", comp.path("moat").asText("")),
                card("政策契合度", comp.path("policyFit").asText("")),
                card("全球化", comp.path("globalization").asText("")),
                card("价格趋势", comp.path("priceTrend").asText("")),
                card("董事长画像", comp.path("chairman").asText("")),
                card("催化剂", comp.path("catalysts").asText(""))
        )));

        // ③ 估值
        sb.append("<h2>③ 估值层面</h2>");
        JsonNode val = analysis.path("valuation");
        sb.append("<div class='meta-grid'>");
        sb.append(metaItem("公司类型", val.path("type").asText("-")));
        String verdictClass = verdictHtmlClass(val.path("verdict").asText(""));
        sb.append(metaItem("综合判定", "<span class='" + verdictClass + "'>" + esc(val.path("verdict").asText("-")) + "</span>"));
        sb.append("</div>");
        JsonNode methods = val.path("methods");
        if (methods.isArray() && methods.size() > 0) {
            sb.append("<table><thead><tr><th>估值方法</th><th>当前值</th><th>合理区间</th><th>结论</th></tr></thead><tbody>");
            for (JsonNode m : methods) {
                String vc = verdictHtmlClass(m.path("verdict").asText(""));
                sb.append("<tr><td>").append(esc(m.path("name").asText(""))).append("</td>")
                  .append("<td>").append(esc(m.path("current").asText(""))).append("</td>")
                  .append("<td>").append(esc(m.path("reasonable").asText(""))).append("</td>")
                  .append("<td class='").append(vc).append("'>").append(esc(m.path("verdict").asText(""))).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("<div class='meta-grid'>");
        sb.append(metaItem("2026目标价", val.path("target2026").asText("-")));
        sb.append(metaItem("2027目标价", val.path("target2027").asText("-")));
        sb.append("</div>");
        if (!val.path("reasoning").asText("").isEmpty()) {
            sb.append("<div class='card'><div class='card-label'>估值依据</div><div class='card-value'>")
              .append(esc(val.path("reasoning").asText(""))).append("</div></div>");
        }

        // ④ 技术
        sb.append("<h2>④ 技术层面</h2>");
        JsonNode tech = analysis.path("technical");
        sb.append(cardGrid(List.of(
                card("趋势线", tech.path("trendLine").asText("")),
                card("均线", tech.path("ma").asText("")),
                card("成交量", tech.path("volume").asText("")),
                card("MACD", tech.path("macd").asText(""))
        )));
        if (!tech.path("verdict").asText("").isEmpty()) {
            sb.append("<div class='card'><strong>综合判定: </strong>").append(esc(tech.path("verdict").asText(""))).append("</div>");
        }

        // ⑤ 资金
        sb.append("<h2>⑤ 资金层面</h2>");
        JsonNode cap = analysis.path("capital");
        sb.append(cardGrid(List.of(
                card("主力资金", cap.path("mainNetIn").asText("")),
                card("北向资金", cap.path("northbound").asText("")),
                card("龙虎榜", cap.path("dragonTiger").asText(""))
        )));
        if (!cap.path("verdict").asText("").isEmpty()) {
            sb.append("<div class='card'><strong>综合判定: </strong>").append(esc(cap.path("verdict").asText(""))).append("</div>");
        }

        // ⑥ 紫苏叶（融合个股分析）
        if (chainNode != null && !chainNode.isMissingNode() && chainNode.size() > 0) {
            sb.append("<h2>⑥ 紫苏叶 · 产业链定位</h2>");
            sb.append(cardGrid(List.of(
                    card("行业", chainNode.path("industry").asText("")),
                    card("位置", chainNode.path("layer").asText("")),
                    card("护城河类型", chainNode.path("moatType").asText("")),
                    card("拆解路径", chainNode.path("chainPath").asText(""))
            )));
            // 竞争格局
            JsonNode competitionNode = chainNode.path("competition");
            if (competitionNode.size() > 0) {
                sb.append("<h3>全球竞争格局</h3>");
                sb.append(cardGrid(List.of(
                        card("全球玩家", competitionNode.path("globalPlayers").asText("")),
                        card("中国位置", competitionNode.path("chinesePosition").asText("")),
                        card("地缘优势", competitionNode.path("geographicAdvantage").asText(""))
                )));
            }
        }

        // ⑦ 九维（融合个股分析）
        if (nineNode != null && !nineNode.isMissingNode() && nineNode.size() > 0) {
            sb.append("<h2>⑦ 高景气九维 · 财务摘要</h2>");
            JsonNode finNode = nineNode.path("financial");
            if (finNode.size() > 0) {
                sb.append("<table><tbody>");
                sb.append("<tr><th>报告期</th><td>").append(esc(finNode.path("latestPeriod").asText("-"))).append("</td></tr>");
                sb.append("<tr><th>ROE</th><td>").append(esc(finNode.path("roe").asText("-"))).append("</td></tr>");
                sb.append("<tr><th>毛利率</th><td>").append(esc(finNode.path("grossMargin").asText("-"))).append("</td></tr>");
                sb.append("<tr><th>净利率</th><td>").append(esc(finNode.path("netMargin").asText("-"))).append("</td></tr>");
                sb.append("<tr><th>净利YoY</th><td>").append(esc(finNode.path("yoyNetProfit").asText("-"))).append("</td></tr>");
                sb.append("</tbody></table>");
            }
            JsonNode mktNode = nineNode.path("market");
            if (mktNode.size() > 0) {
                sb.append("<h3>市场行情</h3>");
                sb.append("<div class='meta-grid'>");
                sb.append(metaItem("收盘价", mktNode.path("close").asText("-")));
                sb.append(metaItem("换手率", mktNode.path("turnover").asText("-")));
                sb.append(metaItem("区间最高", mktNode.path("periodHigh").asText("-")));
                sb.append(metaItem("区间最低", mktNode.path("periodLow").asText("-")));
                sb.append("</div>");
            }
        }

        // ⑧ 催化剂 & 风险
        JsonNode summaryNode = analysis.path("summary");
        JsonNode catalystsNode = comp.path("catalysts");
        if (!catalystsNode.asText("").isEmpty()) {
            sb.append("<h2>⑧ 催化剂</h2><div class='card'><div class='card-value'>")
              .append(esc(catalystsNode.asText(""))).append("</div></div>");
        }

        // ⑨ 总结
        sb.append("<h2>⑨ 总结</h2>");
        JsonNode bulletsNode = summaryNode.path("bullets");
        if (bulletsNode.isArray()) {
            sb.append("<ul>");
            for (JsonNode b : bulletsNode) {
                sb.append("<li>").append(esc(b.asText(""))).append("</li>");
            }
            sb.append("</ul>");
        }
        if (!summaryNode.path("oneLiner").asText("").isEmpty()) {
            sb.append("<div class='card' style='background:#fefce8;border-color:#fde68a;'><strong>一句话结论: </strong>")
              .append(esc(summaryNode.path("oneLiner").asText(""))).append("</div>");
        }

        sb.append("<div class='footer'>本报告由 AI + baostock 公开数据 + 紫苏叶/九维方法论生成 · 不构成投资建议 · 记录ID: ")
          .append(entity.getId()).append("</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private String card(String label, String value) {
        if (value == null || value.isEmpty()) return "";
        return "<div class='card'><div class='card-label'>" + esc(label) + "</div><div class='card-value'>" + esc(value) + "</div></div>";
    }

    private String cardGrid(List<String> cards) {
        String inner = cards.stream().filter(c -> !c.isEmpty()).collect(Collectors.joining(""));
        if (inner.isEmpty()) return "";
        return "<div class='cards-grid'>" + inner + "</div>";
    }

    private String metaItem(String label, String value) {
        return "<div class='meta-item'><div class='meta-label'>" + esc(label) + "</div><div class='meta-value'>" + value + "</div></div>";
    }

    private String verdictHtmlClass(String verdict) {
        if (verdict == null) return "";
        if (verdict.contains("便宜") || verdict.contains("低估")) return "verdict-cheap";
        if (verdict.contains("合理")) return "verdict-fair";
        if (verdict.contains("略贵") || verdict.contains("高估")) return "verdict-expensive";
        if (verdict.contains("泡沫")) return "verdict-bubble";
        return "";
    }

    private String esc(Object o) {
        if (o == null) return "-";
        return String.valueOf(o)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String buildImagePromptFromResult(InvestProsperityPick entity) {
        try {
            JsonNode root = MAPPER.readTree(entity.getResultJson() == null ? "{}" : entity.getResultJson());
            JsonNode summary = root.path("summary");
            String oneLiner = summary.path("oneLiner").asText("");
            JsonNode bullets = summary.path("bullets");
            String existing = summary.path("infographicPrompt").asText("");
            if (!existing.isBlank()) return existing;

            StringBuilder bul = new StringBuilder();
            if (bullets.isArray()) {
                int i = 0;
                for (JsonNode b : bullets) {
                    bul.append((char)('①' + i)).append(' ').append(b.asText()).append("；");
                    i++;
                    if (i >= 6) break;
                }
            }
            return "请生成一张以柔和粉色、淡黄色和浅蓝色为主色调的可爱卡通风格信息图（含猫咪、拟人化表情等元素），" +
                    "主题为「" + entity.getStockName() + " " + entity.getStockCode() + " 景气度选股六维分析摘要」，" +
                    "整体排版从左到右分为三个区块：①行业景气度  ②公司基本面与估值  ③技术与资金面。" +
                    "请用图标 + 短句形式呈现以下要点：" + bul + "结论一句话：" + oneLiner +
                    "。包含醒目的主标题与副标题，整体设计有亲和力，信息密度高。";
        } catch (Exception e) {
            return "请生成一张可爱卡通风格信息图，主题为 " + entity.getStockName() + " 景气度选股摘要。";
        }
    }

    // ================================================================
    // 通用工具
    // ================================================================

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

    // ============ 静态资源 ============

    private static final String SYSTEM_PROMPT =
            "你是一名资深的 A 股价值景气投资分析师，擅长从全球产业趋势、行业周期、国家政策、" +
                    "公司基本面、管理层、估值、技术面、资金面进行全维度分析。" +
                    "请严格按照用户给出的 JSON Schema 输出，不要使用 markdown，" +
                    "不要输出任何解释或前后多余文字，输出必须是合法的 JSON。";

    private static final String JSON_SCHEMA = """
            {
              "industry": {
                "cyclePosition": "上行/下行 + 描述当前所处位置",
                "lastCycleReview": "上一轮完整周期时长、顶底特征以及对比当前位置",
                "next12mForecast": "未来12个月拐点核心触发条件、向上/向下概率与弹性",
                "entryBarrier": "高/中/低，并说明新进入者难易度与现有竞争者增减情况",
                "lifeStage": "导入期/成长期/成熟期/萎缩期",
                "competition": "CR5 市场份额数据 + 公司行业地位",
                "globalResonance": "美/德/日/意/加/印/俄/英 主要国家共振程度与政策支持度"
              },
              "company": {
                "businessMix": "各业务线及其营收占比，新增长曲线",
                "quarterly12": "近12季度营收/归母/扣非净利润 同比环比 + 驱动因子拆分",
                "next2yDriver": "未来2年业绩驱动因素（产能、市占率、提价、成本、新品、海外）",
                "moat": "护城河（品牌/技术/成本/渠道/牌照/规模），可持续性与被颠覆风险",
                "policyFit": "是否国家重点扶持，与十五五规划相关度",
                "globalization": "海外营收过去3年占比走势",
                "priceTrend": "过去1年产品/服务价格变化以及未来1年走势",
                "chairman": "董事长年龄/学历/经历/专业度/企业家精神",
                "catalysts": "概念、故事、股价催化剂"
              },
              "valuation": {
                "type": "成长型/强周期/成熟稳定/亏损或周期底部",
                "methods": [
                  {"name":"PEG/PE/PB/PS/EV-EBITDA/DCF/股息率等","current":"当前值","reasonable":"合理区间","verdict":"便宜/合理/略贵/泡沫"}
                ],
                "verdict": "便宜/合理/高估/泡沫",
                "target2026": "x~y 元",
                "target2027": "x~y 元",
                "reasoning": "估值结论的关键依据"
              },
              "technical": {
                "trendLine": "趋势线判断",
                "ma": "均线判断（多头/空头/纠缠）",
                "volume": "成交量判断",
                "macd": "MACD 信号（金叉/死叉/背离）",
                "verdict": "上升趋势/下降趋势/震荡趋势"
              },
              "capital": {
                "mainNetIn": "主力净流入 5/10/20 日数据或定性",
                "northbound": "北向资金动向",
                "dragonTiger": "龙虎榜信号",
                "verdict": "看好/分歧/谨慎"
              },
              "summary": {
                "bullets": ["要点1", "要点2", "要点3", "要点4", "要点5"],
                "oneLiner": "一句话总体结论",
                "infographicPrompt": "用于生成信息图的中文 prompt（≤500字，柔和粉/黄/蓝色调，可爱卡通风格）"
              }
            }
            """;
}
