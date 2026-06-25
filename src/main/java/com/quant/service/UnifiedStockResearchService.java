package com.quant.service;

import com.quant.dto.stockanalysis.StockAnalysisResponse;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;
import com.quant.service.search.WebSearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedStockResearchService {

    private final TradeStockFinancialRepository financialRepository;
    private final AStockDataQuoteService aStockDataQuoteService;
    private final WebSearchClient webSearchClient;
    private final MiniMaxClient miniMaxClient;
    private final SenseNovaClient senseNovaClient;

    public StockAnalysisResponse buildUnifiedResponse(TradeStockBasic basic,
                                                     Map<String, Object> rawData,
                                                     Map<String, Object> aiAnalysis,
                                                     String method,
                                                     long elapsedMs) {
        Map<String, Object> basicPack = asMap(rawData.get("basic"));
        String code = basic.getStockCode();
        String name = safeText(basicPack.get("code_name"), basic.getStockName(), code);
        Map<String, Object> quote = asMap(rawData.get("quote"));
        Double price = firstDouble(quote == null ? null : quote.get("close"), latestPrice(code));

        Map<String, Object> purple = runPurplePerilla(rawData, name);
        Map<String, Object> nineDimension = runGaoJingQi(rawData, name, price);
        Map<String, Object> financialSummary = buildFinancialSummary(asList(rawData.get("financial_history")));
        List<Map<String, Object>> dbFinancials = buildDbFinancialRows(code);
        Map<String, Object> forecastSummary = buildForecastSummary(rawData, aiAnalysis, basic);
        Map<String, Object> externalExpectation = buildExternalExpectation(name);
        List<String> catalysts = buildCatalysts(rawData, name);
        List<String> risks = buildRisks(rawData, name);
        Map<String, Object> sourceMetadata = buildSourceMetadata(rawData, dbFinancials, forecastSummary, externalExpectation);

        StockAnalysisResponse response = StockAnalysisResponse.builder()
                .ok(true)
                .code(code)
                .name(name)
                .currentPrice(price)
                .method(method)
                .verdict((String) purple.get("verdict"))
                .moatScore((Integer) purple.get("moatScore"))
                .chainPosition(asMap(purple.get("chainPosition")))
                .competition(asMap(purple.get("competition")))
                .threeQuestions(asMap(purple.get("threeQuestions")))
                .financialSummary(financialSummary)
                .nineDimension(nineDimension)
                .analysis(aiAnalysis == null ? Collections.emptyMap() : aiAnalysis)
                .sourceMetadata(sourceMetadata)
                .dbFinancials(dbFinancials)
                .forecastSummary(forecastSummary)
                .externalExpectation(externalExpectation)
                .catalysts(catalysts)
                .risks(risks)
                .rawData(rawData)
                .timestamp(LocalDateTime.now())
                .elapsedMs(elapsedMs)
                .build();
        response.setReportHtml(buildReportHtml(response, basic));
        return response;
    }

    public String buildReportHtml(StockAnalysisResponse r, TradeStockBasic basic) {
        StringBuilder sb = new StringBuilder(24_000);
        sb.append("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'><title>")
                .append(esc(r.getName())).append(" 统一个股研究报告</title>")
                .append("<style>")
                .append("""
                    @page { size: A4; margin: 16mm 14mm; }
                    * { box-sizing: border-box; }
                    body { font-family: "Microsoft YaHei","PingFang SC",sans-serif; color: #172033; font-size: 10.5pt; line-height: 1.6; margin: 0; }
                    h1 { font-size: 22pt; margin: 0 0 6pt; color: #0f9d58; border-bottom: 3px solid #0f9d58; padding-bottom: 6pt; }
                    h2 { font-size: 14pt; margin: 14pt 0 6pt; color: #064e3b; border-left: 4px solid #0f9d58; padding-left: 8pt; }
                    h3 { font-size: 11.5pt; margin: 10pt 0 4pt; color: #1f2937; }
                    .sub { color: #6b7280; font-size: 9pt; margin-bottom: 10pt; }
                    .meta-grid, .source-grid, .cards-grid { display: grid; gap: 6pt; }
                    .meta-grid { grid-template-columns: repeat(4, 1fr); }
                    .source-grid { grid-template-columns: repeat(5, 1fr); margin-top: 8pt; }
                    .cards-grid { grid-template-columns: repeat(2, 1fr); }
                    .meta-item, .card, .source-item { border: 1px solid #dbe4ec; border-radius: 6pt; padding: 6pt 8pt; background: #f8fafc; }
                    .meta-label, .card-label, .source-label { color: #6b7280; font-size: 8pt; }
                    .meta-value, .card-value, .source-value { margin-top: 2pt; }
                    .meta-value { font-size: 11.5pt; font-weight: 600; }
                    .source-value.ok { color: #166534; font-weight: 600; }
                    .source-value.miss { color: #991b1b; font-weight: 600; }
                    .card-value { white-space: pre-wrap; font-size: 10pt; }
                    table { width: 100%; border-collapse: collapse; margin: 6pt 0; font-size: 9.5pt; }
                    th, td { border: 1px solid #d0d7e2; padding: 4pt 6pt; text-align: left; vertical-align: top; }
                    th { background: #ecfdf5; color: #065f46; }
                    ul { margin: 4pt 0; padding-left: 16pt; }
                    .footer { margin-top: 18pt; padding-top: 6pt; border-top: 1px solid #d0d7e2; color: #94a3b8; font-size: 8pt; text-align: center; }
                    .summary-box { background: #fefce8; border: 1px solid #fde68a; border-radius: 6pt; padding: 8pt; }
                """)
                .append("</style></head><body>");

        sb.append("<h1>").append(esc(r.getName())).append(" (").append(esc(r.getCode())).append(") 统一个股研究报告</h1>");
        sb.append("<div class='sub'>景气度六维 + 紫苏叶 + 高景气九维 + baostock/DB/检索摘要 · 生成时间: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("</div>");

        sb.append("<h2>总览</h2><div class='meta-grid'>")
                .append(meta("股票", safeText(r.getName(), r.getCode())))
                .append(meta("现价", r.getCurrentPrice() == null ? "-" : String.format("%.2f 元", r.getCurrentPrice())))
                .append(meta("行业", basic.getSectorNames() == null ? "-" : basic.getSectorNames()))
                .append(meta("综合结论", safeText(summaryLine(r), r.getVerdict(), "-")))
                .append(meta("PE/PB/PS", joinSlash(basic.getPeTtm(), basic.getPb(), basic.getPsTtm())))
                .append(meta("护城河", r.getMoatScore() == null ? "-" : r.getMoatScore() + "/10"))
                .append(meta("方法", safeText(r.getMethod(), "-")))
                .append(meta("耗时", r.getElapsedMs() == null ? "-" : r.getElapsedMs() + " ms"))
                .append("</div>");

        sb.append("<h3>数据来源状态</h3><div class='source-grid'>");
        Map<String, Object> sources = r.getSourceMetadata() == null ? Collections.emptyMap() : r.getSourceMetadata();
        sb.append(sourceCard("DB", asMap(sources.get("db"))));
        sb.append(sourceCard("baostock", asMap(sources.get("baostock"))));
        sb.append(sourceCard("forecast", asMap(sources.get("forecast"))));
        sb.append(sourceCard("web search", asMap(sources.get("webSearch"))));
        sb.append(sourceCard("a-stock-data", asMap(sources.get("aStockData"))));
        sb.append("</div>");

        Map<String, Object> analysis = r.getAnalysis() == null ? Collections.emptyMap() : r.getAnalysis();
        Map<String, Object> industry = asMap(analysis.get("industry"));
        Map<String, Object> company = asMap(analysis.get("company"));
        Map<String, Object> valuation = asMap(analysis.get("valuation"));
        Map<String, Object> technical = asMap(analysis.get("technical"));
        Map<String, Object> capital = asMap(analysis.get("capital"));
        Map<String, Object> summary = asMap(analysis.get("summary"));

        sb.append("<h2>行业景气</h2>").append(cards(List.of(
                card("周期位置", value(industry, "cyclePosition")),
                card("上轮周期复盘", value(industry, "lastCycleReview")),
                card("未来12个月拐点", value(industry, "next12mForecast")),
                card("竞争格局", value(industry, "competition")),
                card("全球共振", value(industry, "globalResonance")),
                card("行业生命周期", value(industry, "lifeStage"))
        )));

        sb.append("<h2>公司质地</h2>").append(cards(List.of(
                card("业务结构", value(company, "businessMix")),
                card("12季度业绩", value(company, "quarterly12")),
                card("未来2年驱动", value(company, "next2yDriver")),
                card("护城河", value(company, "moat")),
                card("政策契合度", value(company, "policyFit")),
                card("董事长画像", value(company, "chairman")),
                card("产业链位置", value(r.getChainPosition(), "layer")),
                card("全球玩家", value(r.getCompetition(), "globalPlayers")),
                card("中国位置", value(r.getCompetition(), "chinesePosition")),
                card("地缘优势", value(r.getCompetition(), "geographicAdvantage")),
                card("下单前三问", joinLines(List.of(
                        "① " + value(r.getThreeQuestions(), "Q1_irreplaceable"),
                        "② " + value(r.getThreeQuestions(), "Q2_competitorCount"),
                        "③ " + value(r.getThreeQuestions(), "Q3_demandTrend")
                )))
        )));

        sb.append("<h2>财务趋势</h2>");
        appendDbFinancialTable(sb, r.getDbFinancials());
        appendBaostockFinancialTable(sb, r.getFinancialSummary());

        sb.append("<h2>估值与预期</h2>").append(cards(List.of(
                card("估值结论", value(valuation, "verdict")),
                card("公司类型", value(valuation, "type")),
                card("2026目标价", value(valuation, "target2026")),
                card("2027目标价", value(valuation, "target2027")),
                card("估值依据", value(valuation, "reasoning")),
                card("forecast 摘要", joinForecastItems(r.getForecastSummary())),
                card("外部预期摘要", value(r.getExternalExpectation(), "summary"))
        )));

        sb.append("<h2>技术与资金</h2>").append(cards(List.of(
                card("技术面", joinLines(List.of(
                        "趋势线: " + value(technical, "trendLine"),
                        "均线: " + value(technical, "ma"),
                        "成交量: " + value(technical, "volume"),
                        "MACD: " + value(technical, "macd"),
                        "结论: " + value(technical, "verdict")
                ))),
                card("资金面", joinLines(List.of(
                        "主力资金: " + value(capital, "mainNetIn"),
                        "北向资金: " + value(capital, "northbound"),
                        "龙虎榜: " + value(capital, "dragonTiger"),
                        "结论: " + value(capital, "verdict")
                ))),
                card("行情区间", joinLines(List.of(
                        "区间最高: " + value(asMap(r.getNineDimension() == null ? null : r.getNineDimension().get("market")), "periodHigh"),
                        "区间最低: " + value(asMap(r.getNineDimension() == null ? null : r.getNineDimension().get("market")), "periodLow"),
                        "区间涨跌幅: " + value(asMap(r.getNineDimension() == null ? null : r.getNineDimension().get("market")), "periodChangePct")
                )))
        )));

        sb.append("<h2>催化剂与风险</h2><div class='cards-grid'>")
                .append(card("催化剂", joinBulletLines(r.getCatalysts())))
                .append(card("风险", joinBulletLines(r.getRisks())))
                .append("</div>");

        sb.append("<h2>一句话结论</h2><div class='summary-box'>");
        if (summary.get("bullets") instanceof List<?> bullets && !bullets.isEmpty()) {
            sb.append("<ul>");
            for (Object bullet : bullets) {
                sb.append("<li>").append(esc(bullet)).append("</li>");
            }
            sb.append("</ul>");
        }
        sb.append("<div>").append(esc(summaryLine(r))).append("</div></div>");

        sb.append("<div class='footer'>统一富报告 · 不构成投资建议 · ")
                .append(esc(r.getCode())).append("</div></body></html>");
        return sb.toString();
    }

    public String normalizeCode(String code) {
        if (code == null) return "";
        String normalized = code.trim().toLowerCase();
        if (normalized.contains(".")) return normalized;
        if (normalized.matches("\\d{6}")) {
            if (normalized.startsWith("60") || normalized.startsWith("68") || normalized.startsWith("90")) return "sh." + normalized;
            if (normalized.startsWith("00") || normalized.startsWith("30") || normalized.startsWith("20")) return "sz." + normalized;
            if (normalized.startsWith("43") || normalized.startsWith("83") || normalized.startsWith("87") || normalized.startsWith("88")) return "bj." + normalized;
        }
        return normalized;
    }

    private Map<String, Object> buildSourceMetadata(Map<String, Object> rawData,
                                                    List<Map<String, Object>> dbFinancials,
                                                    Map<String, Object> forecastSummary,
                                                    Map<String, Object> externalExpectation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", sourceMeta(!dbFinancials.isEmpty(), !dbFinancials.isEmpty() ? "trade_stock_*" : "暂无可用结构化数据"));
        out.put("baostock", sourceMeta(rawData != null && !rawData.isEmpty(), rawData != null && !rawData.isEmpty() ? "pack/financial_history/quote" : "暂无可用结构化数据"));
        boolean forecastAvailable = forecastSummary != null && forecastSummary.get("available") == Boolean.TRUE;
        out.put("forecast", sourceMeta(forecastAvailable, forecastAvailable ? "baostock forecast + DB 估算" : "暂无可用结构化数据"));
        boolean searchAvailable = externalExpectation != null && !"暂无可用结构化数据".equals(externalExpectation.get("summary"));
        out.put("webSearch", sourceMeta(searchAvailable, searchAvailable ? "检索归纳" : "暂无可用结构化数据"));
        out.put("aStockData", sourceMeta(false, "当前仓库仅纳入来源占位，无稳定个股端点"));
        return out;
    }

    private Map<String, Object> sourceMeta(boolean available, String detail) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("available", available);
        meta.put("detail", detail);
        return meta;
    }

    private Map<String, Object> buildForecastSummary(Map<String, Object> rawData,
                                                     Map<String, Object> aiAnalysis,
                                                     TradeStockBasic basic) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : asList(rawData.get("forecast"))) {
            Map<String, Object> forecast = asMap(item);
            if (!forecast.isEmpty()) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("title", safeText(forecast.get("type"), forecast.get("title"), "forecast"));
                normalized.put("content", safeText(forecast.get("content"), forecast.get("desc"), forecast.toString()));
                items.add(normalized);
            }
        }
        Map<String, Object> valuation = asMap(aiAnalysis.get("valuation"));
        out.put("available", !items.isEmpty());
        out.put("items", items);
        out.put("target2026", value(valuation, "target2026"));
        out.put("target2027", value(valuation, "target2027"));
        out.put("psTtm", basic.getPsTtm());
        return out;
    }

    private Map<String, Object> buildExternalExpectation(String stockName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", "暂无可用结构化数据");
        out.put("items", List.of());
        if (!webSearchClient.isEnabled()) {
            return out;
        }
        List<WebSearchClient.SearchResult> results = webSearchClient.search(stockName + " 机构预测 盈利预测 目标价");
        if (results.isEmpty()) {
            return out;
        }
        // 收集前 3 条搜索结果用于摘要
        String raw = results.stream()
                .limit(3)
                .map(r -> {
                    String title = r.getTitle() == null ? "" : r.getTitle();
                    String content = r.getContent() == null ? "" : r.getContent();
                    return "- " + title + "\n  " + content;
                })
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        out.put("summary", summarizeExternalExpectation(stockName, raw, results.get(0)));
        out.put("items", results.stream().map(r -> Map.of(
                "title", r.getTitle(),
                "url", r.getUrl(),
                "content", r.getContent()
        )).toList());
        return out;
    }

    /** 把联网检索出的英文研报/新闻摘要，过 LLM 翻译压缩成 2-3 句中文结论。失败时降级为第一条 content。 */
    private String summarizeExternalExpectation(String stockName, String rawText, WebSearchClient.SearchResult first) {
        if (rawText == null || rawText.isBlank()) return "暂无可用结构化数据";
        String clipped = rawText.length() > 2400 ? rawText.substring(0, 2400) : rawText;
        String sys = "你是中文金融研报助手，专注 A 股个股。把用户提供的英文/外文研究摘要翻译并压缩成 2-3 句中文结论，重点保留目标价、EPS 预测、关键业务驱动。\n"
                + "硬性要求：\n"
                + "1. 必须使用中文输出，不要夹杂英文原句。\n"
                + "2. 数字与货币单位保留原文（如 \"45.63 元\"、\"2024 PE\"）。\n"
                + "3. 控制在 80-150 字之间，分句用句号，不要输出 JSON、不要解释、不要 Markdown。\n"
                + "4. 若信息明显无关或不足，直接输出\"暂无可靠的外部预期数据\"。";
        String user = "股票：" + stockName + "\n以下是联网检索到的原始摘要（可能为英文）：\n" + clipped;
        try {
            String text = null;
            try {
                text = miniMaxClient.chatComplete(sys, user);
            } catch (Exception ignore) {
                text = null;
            }
            if (text == null || text.isBlank()) {
                try {
                    text = senseNovaClient.chatComplete(sys, user);
                } catch (Exception ignore) {
                    text = null;
                }
            }
            if (text != null) {
                String cleaned = text.replaceAll("\\s+", " ").trim();
                if (cleaned.length() > 400) cleaned = cleaned.substring(0, 400) + "…";
                return cleaned;
            }
        } catch (Exception e) {
            log.warn("外部预期摘要翻译失败: {} - {}", stockName, e.getMessage());
        }
        // 降级：优先第一条 content，否则标题，再否则固定占位
        String fallback = first == null ? "" : (first.getContent() != null && !first.getContent().isBlank()
                ? first.getContent()
                : first.getTitle());
        if (fallback == null || fallback.isBlank()) return "暂无可用结构化数据";
        return fallback.length() > 400 ? fallback.substring(0, 400) + "…" : fallback;
    }

    private List<Map<String, Object>> buildDbFinancialRows(String stockCode) {
        List<TradeStockFinancial> rows = financialRepository.findByStockCodeOrderByReportDateDesc(stockCode);
        List<Map<String, Object>> out = new ArrayList<>();
        rows.stream().limit(12).forEach(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reportDate", row.getReportDate() == null ? null : row.getReportDate().toString());
            item.put("revenue", yi(row.getRevenue()));
            item.put("netProfit", yi(row.getNetProfit()));
            item.put("eps", row.getEps());
            item.put("roe", row.getRoe());
            item.put("grossMargin", pct(row.getGrossMargin()));
            item.put("netMargin", pct(row.getNetMargin()));
            item.put("revenueYoy", pct(row.getRevenueYoy()));
            item.put("deductedNetProfitYoy", pct(row.getDeductedNetProfitYoy()));
            out.add(item);
        });
        return out;
    }

    /**
     * 当前股价：统一走 a-stock-data 实时接口，trade_stock_daily 收盘价同步延迟且不准确。
     */
    private Double latestPrice(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return null;
        }
        Map<String, AStockDataQuoteService.QuoteSnapshot> quotes = aStockDataQuoteService.fetchQuotes(List.of(stockCode));
        AStockDataQuoteService.QuoteSnapshot snapshot = quotes == null ? null
                : quotes.get(stockCode.trim().toUpperCase(Locale.ROOT));
        if (snapshot == null || snapshot.latestPrice() == null) {
            return null;
        }
        return snapshot.latestPrice().doubleValue();
    }

    private Map<String, Object> runPurplePerilla(Map<String, Object> raw, String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> industry = asMap(raw.get("industry"));
        String industryName = industry == null ? "未知" : String.valueOf(industry.getOrDefault("industry", "未知"));
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("industry", industryName);
        chain.put("name", name);
        chain.put("layer", inferLayer(industryName, name));
        chain.put("chainPath", inferChainPath(industryName, name));
        chain.put("moatType", inferMoatType(industryName, name));
        result.put("chainPosition", chain);

        Map<String, Object> competition = new LinkedHashMap<>();
        competition.put("globalPlayers", inferCompetitors(industryName, name));
        competition.put("chinesePosition", inferChinesePosition(industryName, name));
        competition.put("geographicAdvantage", inferGeoAdvantage(industryName, name));
        result.put("competition", competition);

        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("Q1_irreplaceable", "需要核实 - 该环节全球供应商数量与对标分析");
        questions.put("Q2_competitorCount", "需要核实 - 国内/全球具体玩家数");
        questions.put("Q3_demandTrend", "需要核实 - 下游Capex订单趋势");
        result.put("threeQuestions", questions);

        int moat = calcMoat(industryName, name);
        result.put("moatScore", moat);
        result.put("verdict", moat >= 8 ? "盯住/就是它了" : moat >= 6 ? "盯住" : moat >= 4 ? "观望" : "回避");
        return result;
    }

    private Map<String, Object> runGaoJingQi(Map<String, Object> raw, String name, Double price) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> finHistory = asList(raw.get("financial_history"));
        Map<String, Object> fin = new LinkedHashMap<>();
        if (!finHistory.isEmpty()) {
            Map<String, Object> latest = asMap(finHistory.get(finHistory.size() - 1));
            Map<String, Object> profitability = asMap(latest.get("profitability"));
            Map<String, Object> growth = asMap(latest.get("growth"));
            fin.put("latestPeriod", latest.get("statDate"));
            fin.put("roe", formatPct(profitability.get("roe_avg")));
            fin.put("grossMargin", formatPct(profitability.get("gp_margin")));
            fin.put("netMargin", formatPct(profitability.get("np_margin")));
            fin.put("yoyNetProfit", formatPct(growth.get("yoy_ni")));
            fin.put("epsTtm", profitability.get("eps_ttm"));
        }
        out.put("financial", fin);

        Map<String, Object> valuation = new LinkedHashMap<>();
        valuation.put("currentPrice", price);
        out.put("valuation", valuation);

        Map<String, Object> quote = asMap(raw.get("quote"));
        Map<String, Object> market = new LinkedHashMap<>();
        market.put("close", parseDouble(quote.get("close")));
        market.put("turnover", formatPct(quote.get("turn")));
        market.put("volume", parseDouble(quote.get("volume")));
        market.put("periodHigh", quote.get("period_high"));
        market.put("periodLow", quote.get("period_low"));
        market.put("periodChangePct", formatPct(quote.get("period_change_pct")));
        out.put("market", market);
        out.put("company", asMap(raw.get("basic")));
        out.put("industry", asMap(raw.get("industry")));
        return out;
    }

    private Map<String, Object> buildFinancialSummary(List<Object> finHistory) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (finHistory.isEmpty()) return summary;
        List<String> labels = new ArrayList<>();
        List<Double> roe = new ArrayList<>();
        List<Double> gm = new ArrayList<>();
        List<Double> nm = new ArrayList<>();
        List<Double> yoyNi = new ArrayList<>();
        for (Object row : finHistory) {
            Map<String, Object> rec = asMap(row);
            labels.add(String.valueOf(rec.get("statDate")));
            Map<String, Object> profitability = asMap(rec.get("profitability"));
            Map<String, Object> growth = asMap(rec.get("growth"));
            roe.add(parseDouble(profitability.get("roe_avg")));
            gm.add(parseDouble(profitability.get("gp_margin")));
            nm.add(parseDouble(profitability.get("np_margin")));
            yoyNi.add(parseDouble(growth.get("yoy_ni")));
        }
        summary.put("periodLabels", labels);
        summary.put("roeList", roe);
        summary.put("grossMarginList", gm);
        summary.put("netMarginList", nm);
        summary.put("yoyNetProfitList", yoyNi);
        return summary;
    }

    private List<String> buildCatalysts(Map<String, Object> raw, String name) {
        List<String> catalysts = new ArrayList<>();
        List<Object> forecast = asList(raw.get("forecast"));
        if (!forecast.isEmpty()) catalysts.add("业绩预告/快报: " + forecast.size() + " 条记录");
        List<Object> finHistory = asList(raw.get("financial_history"));
        if (finHistory.size() >= 2) {
            Map<String, Object> latest = asMap(finHistory.get(finHistory.size() - 1));
            Map<String, Object> prev = asMap(finHistory.get(finHistory.size() - 2));
            Double curNm = parseDouble(asMap(latest.get("profitability")).get("np_margin"));
            Double prevNm = parseDouble(asMap(prev.get("profitability")).get("np_margin"));
            if (curNm != null && prevNm != null && curNm - prevNm > 0.05) {
                catalysts.add(String.format("净利率季度环比 +%.1fpp, 业绩反转信号", (curNm - prevNm) * 100));
            }
        }
        catalysts.add("关注下游订单与新品放量进度");
        return catalysts;
    }

    private List<String> buildRisks(Map<String, Object> raw, String name) {
        List<String> risks = new ArrayList<>();
        List<Object> finHistory = asList(raw.get("financial_history"));
        if (!finHistory.isEmpty()) {
            Map<String, Object> latest = asMap(finHistory.get(finHistory.size() - 1));
            Map<String, Object> profitability = asMap(latest.get("profitability"));
            Double roe = parseDouble(profitability.get("roe_avg"));
            Double nm = parseDouble(profitability.get("np_margin"));
            if (roe != null && roe < 0.05) risks.add(String.format("ROE仅%.2f%%, 盈利质量偏弱", roe * 100));
            if (nm != null && nm < 0) risks.add("净利率为负, 经营承压");
        }
        risks.add("行业景气波动可能导致估值压缩");
        risks.add("若外部预期落空，短期股价弹性会明显回撤");
        return risks;
    }

    private void appendDbFinancialTable(StringBuilder sb, List<Map<String, Object>> rows) {
        sb.append("<h3>DB 最近12季财务</h3>");
        if (rows == null || rows.isEmpty()) {
            sb.append("<div class='card'>暂无可用结构化数据</div>");
            return;
        }
        sb.append("<table><thead><tr><th>报告期</th><th>营收(亿)</th><th>净利润(亿)</th><th>EPS</th><th>ROE</th><th>毛利率</th><th>净利率</th><th>营收YoY</th><th>扣非YoY</th></tr></thead><tbody>");
        for (Map<String, Object> row : rows) {
            sb.append("<tr><td>").append(esc(row.get("reportDate"))).append("</td>")
                    .append("<td>").append(esc(row.get("revenue"))).append("</td>")
                    .append("<td>").append(esc(row.get("netProfit"))).append("</td>")
                    .append("<td>").append(esc(row.get("eps"))).append("</td>")
                    .append("<td>").append(esc(row.get("roe"))).append("</td>")
                    .append("<td>").append(esc(row.get("grossMargin"))).append("</td>")
                    .append("<td>").append(esc(row.get("netMargin"))).append("</td>")
                    .append("<td>").append(esc(row.get("revenueYoy"))).append("</td>")
                    .append("<td>").append(esc(row.get("deductedNetProfitYoy"))).append("</td></tr>");
        }
        sb.append("</tbody></table>");
    }

    private void appendBaostockFinancialTable(StringBuilder sb, Map<String, Object> fin) {
        sb.append("<h3>baostock 财务趋势</h3>");
        List<String> labels = fin == null ? List.of() : castStringList(fin.get("periodLabels"));
        if (labels.isEmpty()) {
            sb.append("<div class='card'>暂无可用结构化数据</div>");
            return;
        }
        List<Double> roe = castDoubleList(fin.get("roeList"));
        List<Double> gm = castDoubleList(fin.get("grossMarginList"));
        List<Double> nm = castDoubleList(fin.get("netMarginList"));
        List<Double> yoy = castDoubleList(fin.get("yoyNetProfitList"));
        sb.append("<table><thead><tr><th>指标</th>");
        for (String label : labels) sb.append("<th>").append(esc(label)).append("</th>");
        sb.append("</tr></thead><tbody>");
        metricRow(sb, "ROE", roe);
        metricRow(sb, "毛利率", gm);
        metricRow(sb, "净利率", nm);
        metricRow(sb, "净利YoY", yoy);
        sb.append("</tbody></table>");
    }

    private void metricRow(StringBuilder sb, String label, List<Double> values) {
        sb.append("<tr><th>").append(esc(label)).append("</th>");
        for (Double value : values) {
            sb.append("<td>").append(value == null ? "-" : String.format("%.2f%%", value * 100)).append("</td>");
        }
        sb.append("</tr>");
    }

    private String sourceCard(String label, Map<String, Object> meta) {
        boolean available = meta != null && Boolean.TRUE.equals(meta.get("available"));
        return "<div class='source-item'><div class='source-label'>" + esc(label) + "</div><div class='source-value "
                + (available ? "ok" : "miss") + "'>" + (available ? "可用" : "缺失/占位") + "</div><div class='card-value'>"
                + esc(meta == null ? "暂无可用结构化数据" : meta.get("detail")) + "</div></div>";
    }

    private String cards(List<String> cards) {
        return "<div class='cards-grid'>" + cards.stream().filter(s -> !s.isBlank()).reduce("", String::concat) + "</div>";
    }

    private String card(String label, String value) {
        if (value == null || value.isBlank()) return "";
        return "<div class='card'><div class='card-label'>" + esc(label) + "</div><div class='card-value'>" + esc(value) + "</div></div>";
    }

    private String meta(String label, String value) {
        return "<div class='meta-item'><div class='meta-label'>" + esc(label) + "</div><div class='meta-value'>" + esc(value) + "</div></div>";
    }

    private String summaryLine(StockAnalysisResponse response) {
        Map<String, Object> summary = asMap(response.getAnalysis() == null ? null : response.getAnalysis().get("summary"));
        return safeText(summary.get("oneLiner"), response.getVerdict(), "暂无可用结构化数据");
    }

    private String joinForecastItems(Map<String, Object> forecastSummary) {
        if (forecastSummary == null) return "暂无可用结构化数据";
        List<Map<String, Object>> items = castMapList(forecastSummary.get("items"));
        if (items.isEmpty()) return "暂无可用结构化数据";
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> item : items) {
            lines.add(safeText(item.get("title"), "") + ": " + safeText(item.get("content"), "-"));
        }
        return joinLines(lines);
    }

    private String joinBulletLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "暂无可用结构化数据";
        return joinLines(lines.stream().map(s -> "• " + s).toList());
    }

    private String joinLines(List<String> lines) {
        return String.join("\n", lines.stream().filter(s -> s != null && !s.isBlank()).toList());
    }

    private String joinSlash(Object a, Object b, Object c) {
        List<String> values = new ArrayList<>();
        if (a != null) values.add(String.valueOf(a));
        if (b != null) values.add(String.valueOf(b));
        if (c != null) values.add(String.valueOf(c));
        return values.isEmpty() ? "-" : String.join(" / ", values);
    }

    private String value(Map<String, Object> map, String key) {
        return map == null ? "" : safeText(map.get(key), "");
    }

    private String safeText(Object... values) {
        for (Object value : values) {
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) return text;
            }
        }
        return "";
    }

    private BigDecimal yi(BigDecimal value) {
        if (value == null) return null;
        return value.divide(BigDecimal.valueOf(100_000_000L), 2, RoundingMode.HALF_UP);
    }

    private String pct(BigDecimal value) {
        if (value == null) return null;
        return value.stripTrailingZeros().toPlainString() + "%";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castMapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) out.add(asMap(item));
            return out;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Double> castDoubleList(Object value) {
        return value instanceof List<?> list ? (List<Double>) list : List.of();
    }

    private Double firstDouble(Object primary, Double fallback) {
        Double value = parseDouble(primary);
        return value != null ? value : fallback;
    }

    private Double parseDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
    }

    private String formatPct(Object value) {
        Double pct = parseDouble(value);
        return pct == null ? "" : String.format("%.2f%%", pct * 100);
    }

    private String esc(Object value) {
        if (value == null) return "-";
        return String.valueOf(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
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
            return "AI/HBM需求 → 存储原厂 → 测试设备供应商";
        }
        if (name.contains("中微") || name.contains("北方华创")) {
            return "AI/HBM需求 → 晶圆厂 → 刻蚀/沉积设备";
        }
        return "需结合行业上下游分析";
    }

    private String inferMoatType(String industry, String name) {
        if (industry.contains("半导体") || industry.contains("C35")) {
            return "地缘保护型 + 技术壁垒";
        }
        return "需结合个股分析";
    }

    private String inferCompetitors(String industry, String name) {
        if (name.contains("精智达")) return "爱德万 / 泰瑞达 / 精智达";
        if (name.contains("华峰")) return "泰瑞达 / 爱德万 / 华峰测控";
        if (name.contains("长川")) return "爱德万 / 泰瑞达 / 长川科技";
        return "需结合行业研究";
    }

    private String inferChinesePosition(String industry, String name) {
        if (industry.contains("半导体") || industry.contains("C35")) {
            return "国产替代核心受益方";
        }
        return "需结合行业格局";
    }

    private String inferGeoAdvantage(String industry, String name) {
        if (industry.contains("半导体") || industry.contains("C35")) {
            return "外部限制下的国产替代窗口";
        }
        return "需结合地缘政治分析";
    }

    private int calcMoat(String industry, String name) {
        int score = 5;
        if (industry.contains("半导体") || industry.contains("C35")) score += 3;
        if (name.contains("精智达") || name.contains("华峰")) score += 1;
        return Math.min(10, score);
    }
}
