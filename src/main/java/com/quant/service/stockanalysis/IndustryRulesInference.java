package com.quant.service.stockanalysis;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 紫苏叶产业链定位 + 护城河评分的纯规则 inference（不依赖任何外部数据源）。
 *
 * <p>从 {@code StockAnalysisService.runPurplePerilla} 拆出 — 仅做行业关键字匹配 + 启发式打分。 类似 {@code
 * ProsperityPickIndustryRules} 的模式：纯函数 over (industry, name) → 评分 + 文本字段。
 */
@Service
public class IndustryRulesInference {

  /**
   * 紫苏叶 (chain position + competition + 三问 + 护城河评分 + verdict)。
   *
   * @param raw 来自 baostock pack 的 rawData map（含 "industry" key）
   * @param name 公司中文简称
   * @return 紫苏叶段落 map，键含 chainPosition / competition / threeQuestions / moatScore / verdict
   */
  public Map<String, Object> runPurplePerilla(Map<String, Object> raw, String name) {
    Map<String, Object> result = new HashMap<>();
    Map<String, Object> industry = asMap(raw.get("industry"));
    String industryName =
        industry == null ? "未知" : String.valueOf(industry.getOrDefault("industry", "未知"));
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

  public String inferLayer(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("电子") || industry.contains("C35")) {
      if (name.contains("测") || name.contains("精")) return "第4层 - 测试设备";
      if (name.contains("蚀")) return "第3层 - 刻蚀设备";
      if (name.contains("光")) return "第3层 - 光刻/检测设备";
    }
    if (industry.contains("医药") || industry.contains("生物")) return "第3-4层 - 创新药/医疗器械";
    return "需结合个股业务定位";
  }

  public String inferChainPath(String industry, String name) {
    if (name.contains("精智达") || name.contains("华峰") || name.contains("长川")) {
      return "AI/HBM需求 → 存储原厂(三星/海力士/长江存储/长鑫) → 测试设备供应商";
    }
    if (name.contains("中微") || name.contains("北方华创")) {
      return "AI/HBM需求 → 晶圆厂 → 刻蚀/沉积设备";
    }
    return "需结合行业上下游分析";
  }

  public String inferMoatType(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("C35")) {
      return "地缘保护型(出口管制+国产替代政策) + 技术壁垒(高端设备研发周期3-5年)";
    }
    return "需结合个股分析";
  }

  public String inferCompetitors(String industry, String name) {
    if (name.contains("精智达")) return "爱德万(日本) / 泰瑞达(美国) / 精智达(国内唯一)";
    if (name.contains("华峰")) return "泰瑞达(美国) / 爱德万(日本) / 华峰测控(国内领先)";
    if (name.contains("长川")) return "爱德万 / 泰瑞达 / 长川科技 / 分选机其他玩家";
    return "需结合行业研究";
  }

  public String inferChinesePosition(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("C35")) {
      return "国产替代核心受益方, 但高端产品仍由外资主导";
    }
    return "需结合行业格局";
  }

  public String inferGeoAdvantage(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("C35")) {
      return "美对华14nm以下设备出口管制 → 国产替代窗口期3-5年";
    }
    return "需结合地缘政治分析";
  }

  public int calcMoat(String industry, String name) {
    int score = 5;
    if (industry.contains("半导体") || industry.contains("C35")) score += 3;
    if (name.contains("精智达") || name.contains("华峰")) score += 1;
    if (name.contains("唯一") || name.contains("稀缺")) score += 1;
    return Math.min(10, score);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    if (o instanceof Map) return (Map<String, Object>) o;
    return new HashMap<>();
  }
}
