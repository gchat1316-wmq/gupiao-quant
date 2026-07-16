package com.quant.service.stockanalysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 高景气九维 (gaojingqi) 段落组装 + 跨文件复用的工具函数 (asMap / asList / parseDouble / formatPct / safe)。
 *
 * <p>从 {@code StockAnalysisService.runGaoJingQi / buildFinancialSummary / buildCatalysts /
 * buildRisks} 拆出。 工具函数也归在这里， 让 prompt builder / ai caller 都能复用，避免重复实现。
 */
@Service
public class NineDimensionComposer {

  /** 高景气九维段落：financial + valuation + market + company + industry + forecast + dividend。 */
  public Map<String, Object> runGaoJingQi(Map<String, Object> raw, String name, Double price) {
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

  /**
   * 财务历史 (近 N 季度) → 图表用序列：periodLabels / roeList / grossMarginList / netMarginList /
   * yoyNetProfitList。
   */
  public Map<String, Object> buildFinancialSummary(List<Object> finHistory) {
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

  /** 股价催化剂：从 forecast + 净利率环比 + Capex 关注点 拼出。 */
  public List<String> buildCatalysts(Map<String, Object> raw, String name) {
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

  /** 风险提示：ROE/净利率阈值 + 行业 β 风险通用条目。 */
  public List<String> buildRisks(Map<String, Object> raw, String name) {
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

  // ============================================================
  // 通用工具 (跨文件复用, 集中在此避免散落)
  // ============================================================
  @SuppressWarnings("unchecked")
  public Map<String, Object> asMap(Object o) {
    if (o instanceof Map) return (Map<String, Object>) o;
    return Collections.emptyMap();
  }

  @SuppressWarnings("unchecked")
  public List<Object> asList(Object o) {
    if (o == null) return Collections.emptyList();
    if (o instanceof List) return (List<Object>) o;
    return Collections.emptyList();
  }

  public Double parseDouble(Object o) {
    if (o == null) return null;
    if (o instanceof Number) return ((Number) o).doubleValue();
    try {
      return Double.parseDouble(String.valueOf(o));
    } catch (Exception e) {
      return null;
    }
  }

  public String formatPct(Object o) {
    Double d = parseDouble(o);
    if (d == null) return "N/A";
    return String.format("%.2f%%", d * 100);
  }

  public String safe(Object v) {
    return v == null ? "" : v.toString();
  }
}
