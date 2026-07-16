package com.quant.service.prosperitystrong;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.quant.dto.invest.ProsperityPickResultDTO;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.search.WebSearchClient;

import lombok.RequiredArgsConstructor;

/**
 * Assembles the six-dimension AI prompt (industry / company / valuation / technical / capital /
 * summary) by concatenating profile + last-12-quarter financials + baostock market slice + a few
 * web search snippets. Pure string-building — AI invocation itself lives on the facade.
 */
@Component
@RequiredArgsConstructor
public class ProsperityPickAiPromptBuilder {

  private static final int FINANCIAL_QUARTERS = 12;

  private final TradeStockFinancialRepository financialRepo;
  private final WebSearchClient webSearchClient;

  public String buildPrompt(
      ProsperityPickResultDTO.Profile profile,
      TradeStockBasic basic,
      java.util.Map<String, Object> baostockData) {
    StringBuilder sb = new StringBuilder();
    sb.append("分析日期: ").append(java.time.LocalDate.now()).append("\n");
    sb.append("公司: ")
        .append(profile.getStockName())
        .append(" ")
        .append(profile.getStockCode())
        .append(" (A股)\n");
    if (profile.getCurrentPrice() != null)
      sb.append("现价: ").append(profile.getCurrentPrice()).append(" 元\n");
    if (profile.getTotalMarketCap() != null)
      sb.append("总市值: ").append(profile.getTotalMarketCap()).append(" 亿元\n");
    if (profile.getIndustry() != null)
      sb.append("所属行业: ").append(profile.getIndustry()).append("\n");
    if (profile.getPeTtm() != null) sb.append("PE-TTM: ").append(profile.getPeTtm()).append("\n");
    if (profile.getPb() != null) sb.append("PB: ").append(profile.getPb()).append("\n");
    if (profile.getPsTtm() != null) sb.append("PS-TTM: ").append(profile.getPsTtm()).append("\n");

    // DB 12 季度财务
    List<TradeStockFinancial> records =
        financialRepo.findByStockCodeOrderByReportDateDesc(basic.getStockCode()).stream()
            .limit(FINANCIAL_QUARTERS)
            .collect(Collectors.toList());
    if (!records.isEmpty()) {
      sb.append("\n最近 ").append(records.size()).append(" 季度财务（单位：元）：\n");
      sb.append("报告期 | 营收 | 净利润 | 扣非净利润同比 | 毛利率 | 净利率 | ROE\n");
      for (TradeStockFinancial f : records) {
        sb.append(f.getReportDate())
            .append(" | ")
            .append(safe(f.getRevenue()))
            .append(" | ")
            .append(safe(f.getNetProfit()))
            .append(" | ")
            .append(safe(f.getDeductedNetProfitYoy()))
            .append(" | ")
            .append(safe(f.getGrossMargin()))
            .append(" | ")
            .append(safe(f.getNetMargin()))
            .append(" | ")
            .append(safe(f.getRoe()))
            .append("\n");
      }
    }

    // baostock 真实行情数据（如果有）
    if (baostockData != null && !baostockData.isEmpty()) {
      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> quote =
          (java.util.Map<String, Object>) baostockData.get("quote");
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
      @SuppressWarnings("unchecked")
      List<Object> finHistory = (List<Object>) baostockData.get("financial_history");
      if (finHistory != null && !finHistory.isEmpty()) {
        sb.append("\nbaostock 财务历史 (近 ").append(finHistory.size()).append(" 季度):\n");
        sb.append("报告期 | ROE | 毛利率 | 净利率 | 净利YoY\n");
        for (Object o : finHistory) {
          @SuppressWarnings("unchecked")
          java.util.Map<String, Object> rec = (java.util.Map<String, Object>) o;
          if (rec == null) continue;
          @SuppressWarnings("unchecked")
          java.util.Map<String, Object> p =
              (java.util.Map<String, Object>) rec.get("profitability");
          @SuppressWarnings("unchecked")
          java.util.Map<String, Object> g = (java.util.Map<String, Object>) rec.get("growth");
          sb.append(safe(rec.get("statDate")))
              .append(" | ")
              .append(safe(p == null ? null : p.get("roe_avg")))
              .append(" | ")
              .append(safe(p == null ? null : p.get("gp_margin")))
              .append(" | ")
              .append(safe(p == null ? null : p.get("np_margin")))
              .append(" | ")
              .append(safe(g == null ? null : g.get("yoy_ni")))
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

  /** Extract JSON substring from a possibly markdown-fenced or prose-wrapped LLM response. */
  public String extractJson(String raw) {
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

  private static final String JSON_SCHEMA =
      """
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
