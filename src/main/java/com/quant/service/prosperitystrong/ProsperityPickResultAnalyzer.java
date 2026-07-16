package com.quant.service.prosperitystrong;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.dto.invest.ProsperityPickRecentDTO;
import com.quant.dto.invest.ProsperityPickResultDTO;
import com.quant.entity.InvestProsperityPick;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestProsperityPickRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-stock analysis pipeline helper. Owns:
 *
 * <ul>
 *   <li>紫苏叶产业链定位 + 高景气九维分析 (rules live in {@link ProsperityPickIndustryRules})
 *   <li>Catalyst / risk bullet extraction from baostock financial_history
 *   <li>Financial-trend reconstruction (periodLabels + roe/gross/net/yoy series) for the report
 *   <li>紫苏叶/九维 pipeline stage orchestration ({@link #runPurplePerillaStage}) — given baostock data,
 *       produces the chain position + nine-dim nodes + catalysts/risks/moat in one call
 *   <li>Result DTO assembly ({@link #toResultDTO} / {@link #toRecentDTO}) — shared by both the
 *       analyze() and get()/recent() paths
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProsperityPickResultAnalyzer {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ProsperityPickProfileBuilder profileBuilder;
  private final ProsperityPickIndustryRules industryRules;

  /**
   * Result of the 紫苏叶/九维 stage. Carries all the pieces that the facade needs to feed back into the
   * result DTO and the persisted entity.
   */
  public record PurplePerillaOutcome(
      JsonNode chainPosition,
      JsonNode nineDimension,
      Integer moatScore,
      String verdict,
      List<String> catalysts,
      List<String> risks) {}

  // ================================================================
  // 紫苏叶 + 九维 编排
  // ================================================================

  /**
   * Runs 紫苏叶 chain-position + 九维 financial block + catalyst/risk extraction in one call. Returns
   * nulls/empties when baostock data is missing or any step throws so the facade can treat
   * success/failure uniformly.
   */
  public PurplePerillaOutcome runPurplePerillaStage(
      Map<String, Object> baostockData, TradeStockBasic basic, BigDecimal currentPrice) {
    if (baostockData == null || baostockData.isEmpty()) {
      return new PurplePerillaOutcome(null, null, null, null, new ArrayList<>(), new ArrayList<>());
    }
    try {
      Map<String, Object> purplePerilla = runPurplePerilla(baostockData, basic.getStockName());
      JsonNode chainPosition = toJsonNode(purplePerilla.get("chainPosition"));
      Integer moatScore = (Integer) purplePerilla.get("moatScore");
      String verdict = (String) purplePerilla.get("verdict");

      Map<String, Object> gaoJingQi =
          runGaoJingQi(baostockData, basic.getStockName(), currentPrice);
      JsonNode nineDimension = toJsonNode(gaoJingQi);

      List<String> catalysts = buildCatalysts(baostockData, basic.getStockName());
      List<String> risks = buildRisks(baostockData, basic.getStockName());
      log.info("紫苏叶+九维分析完成: moatScore={}, verdict={}", moatScore, verdict);
      return new PurplePerillaOutcome(
          chainPosition, nineDimension, moatScore, verdict, catalysts, risks);
    } catch (Exception e) {
      log.warn("紫苏叶/九维分析失败，继续 AI 分析: {}", e.getMessage());
      return new PurplePerillaOutcome(null, null, null, null, new ArrayList<>(), new ArrayList<>());
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> runPurplePerilla(Map<String, Object> raw, String name) {
    Map<String, Object> result = new HashMap<>();
    Map<String, Object> industry = asMap(raw.get("industry"));
    String industryName =
        industry == null ? "未知" : String.valueOf(industry.getOrDefault("industry", "未知"));

    Map<String, Object> chain = new HashMap<>();
    chain.put("industry", industryName);
    chain.put("name", name);
    chain.put("layer", industryRules.inferLayer(industryName, name));
    chain.put("chainPath", industryRules.inferChainPath(industryName, name));
    chain.put("moatType", industryRules.inferMoatType(industryName, name));
    result.put("chainPosition", chain);

    Map<String, Object> comp = new HashMap<>();
    comp.put("globalPlayers", industryRules.inferCompetitors(industryName, name));
    comp.put("chinesePosition", industryRules.inferChinesePosition(industryName, name));
    comp.put("geographicAdvantage", industryRules.inferGeoAdvantage(industryName, name));
    result.put("competition", comp);

    Map<String, Object> q = new HashMap<>();
    q.put("Q1_irreplaceable", "需要核实 - 该环节全球供应商数量与对标分析");
    q.put("Q2_competitorCount", "需要核实 - 国内/全球具体玩家数");
    q.put("Q3_demandTrend", "需要核实 - 下游Capex订单趋势");
    result.put("threeQuestions", q);

    int moat = industryRules.calcMoat(industryName, name);
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
  public Map<String, Object> runGaoJingQi(Map<String, Object> raw, String name, BigDecimal price) {
    Map<String, Object> nine = new HashMap<>();
    List<Object> finHistory = asList(raw.get("financial_history"));
    Map<String, Object> fin = new HashMap<>();
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

    Map<String, Object> valuation = new HashMap<>();
    valuation.put("currentPrice", price);
    nine.put("valuation", valuation);

    Map<String, Object> quote = asMap(raw.get("quote"));
    Map<String, Object> mkt = new HashMap<>();
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

  // ================================================================
  // 财务趋势 / 催化剂 / 风险
  // ================================================================

  @SuppressWarnings("unchecked")
  public ProsperityPickResultDTO.FinancialSummary buildFinancialSummaryFromBaostock(
      Map<String, Object> baostockData) {
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
  public List<String> buildCatalysts(Map<String, Object> raw, String name) {
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
  public List<String> buildRisks(Map<String, Object> raw, String name) {
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

  // ================================================================
  // 实体持久化（analyze() 第 ⑤ 步）
  // ================================================================

  /**
   * Loads (or creates) the day's {@link InvestProsperityPick} for the stock and fills it with the
   * AI / 紫苏叶 / baostock analysis results. Force mode clears the cached infographic.
   */
  public InvestProsperityPick buildEntity(
      TradeStockBasic basic,
      LocalDate today,
      String aiJson,
      PurplePerillaOutcome outcome,
      Map<String, Object> baostockData,
      int elapsedMs,
      boolean force,
      InvestProsperityPickRepository repo) {
    InvestProsperityPick entity =
        repo.findByStockCodeAndAnalysisDate(basic.getStockCode(), today)
            .orElseGet(InvestProsperityPick::new);
    entity.setStockCode(basic.getStockCode());
    entity.setStockName(basic.getStockName() != null ? basic.getStockName() : basic.getStockCode());
    entity.setAnalysisDate(today);
    entity.setResultJson(aiJson);
    entity.setDegraded(0);
    entity.setMoatScore(outcome.moatScore());
    entity.setVerdict(outcome.verdict());
    entity.setElapsedMs(elapsedMs);
    if (outcome.chainPosition() != null)
      entity.setChainPosition(outcome.chainPosition().toString());
    if (outcome.nineDimension() != null)
      entity.setNineDimension(outcome.nineDimension().toString());
    if (baostockData != null) {
      try {
        entity.setBaostockData(MAPPER.writeValueAsString(baostockData));
      } catch (Exception e) {
        log.warn("序列化 baostock 数据失败", e);
      }
    }
    if (force) {
      entity.setImageUrl(null);
      entity.setImagePrompt(null);
    }
    return entity;
  }

  // ================================================================
  // DTO 组装
  // ================================================================

  public ProsperityPickResultDTO toResultDTO(
      InvestProsperityPick entity, TradeStockBasic basic, boolean cached) {
    JsonNode analysis = readAnalysis(entity.getResultJson());
    JsonNode chainPositionNode = readAnalysis(entity.getChainPosition());
    JsonNode nineDimensionNode = readAnalysis(entity.getNineDimension());
    ProsperityPickResultDTO.FinancialSummary finSummary = null;

    if (entity.getBaostockData() != null) {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> baostock = MAPPER.readValue(entity.getBaostockData(), Map.class);
        finSummary = buildFinancialSummaryFromBaostock(baostock);
      } catch (Exception e) {
        log.warn("解析 baostock 数据失败", e);
      }
    }

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
        .profile(profileBuilder.buildProfile(basic))
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

  public ProsperityPickRecentDTO toRecentDTO(InvestProsperityPick entity) {
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
    BigDecimal currentPrice = null;
    try {
      JsonNode profileNode = root.path("profile");
      if (profileNode.has("currentPrice")) {
        currentPrice = profileNode.get("currentPrice").decimalValue();
      }
    } catch (Exception ignore) {
    }

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
    try {
      return Double.parseDouble(String.valueOf(o));
    } catch (Exception e) {
      return null;
    }
  }

  private String formatPct(Object o) {
    Double d = parseDouble(o);
    if (d == null) return "N/A";
    return String.format("%.2f%%", d * 100);
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
}
