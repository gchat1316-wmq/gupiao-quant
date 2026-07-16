package com.quant.service.prosperitystrong;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.entity.InvestProsperityPick;
import com.quant.entity.TradeStockBasic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders the per-stock A4-style HTML report (industry / company / valuation / technical / capital
 * / 紫苏叶 / 九维 / catalysts / summary) and the infographic-prompt builder. Pure string-building; the
 * report HTML is then persisted on the entity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProsperityPickReportRenderer {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ProsperityPickProfileBuilder profileBuilder;

  public String buildReportHtml(InvestProsperityPick entity, TradeStockBasic basic) {
    JsonNode analysis = readAnalysis(entity.getResultJson());
    JsonNode chainNode = readAnalysis(entity.getChainPosition());
    JsonNode nineNode = readAnalysis(entity.getNineDimension());
    com.quant.dto.invest.ProsperityPickResultDTO.Profile profile =
        profileBuilder.buildProfile(basic);

    StringBuilder sb = new StringBuilder(16384);
    sb.append("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'><title>")
        .append(esc(entity.getStockName()))
        .append(" 全维度分析报告</title>")
        .append("<style>")
        .append(STYLE)
        .append("</style></head><body>");

    // 标题
    sb.append("<h1>")
        .append(esc(entity.getStockName()))
        .append(" (")
        .append(esc(entity.getStockCode()))
        .append(") 全维度分析报告</h1>");
    sb.append("<div class='sub'>景气度选股 · AI六维研报 + 紫苏叶产业链 + 高景气九维 · 数据源: baostock/DB/联网检索 · 生成时间: ")
        .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        .append("</div>");

    // 总览
    sb.append("<h2>总览</h2>");
    sb.append("<div class='meta-grid'>");
    sb.append(
        metaItem("现价", profile.getCurrentPrice() == null ? "-" : profile.getCurrentPrice() + " 元"));
    sb.append(
        metaItem(
            "总市值", profile.getTotalMarketCap() == null ? "-" : profile.getTotalMarketCap() + " 亿"));
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
    sb.append(
        cardGrid(
            List.of(
                card("周期位置", ind.path("cyclePosition").asText("")),
                card("上轮周期复盘", ind.path("lastCycleReview").asText("")),
                card("12个月拐点预判", ind.path("next12mForecast").asText("")),
                card("进入壁垒", ind.path("entryBarrier").asText("")),
                card("行业生命周期", ind.path("lifeStage").asText("")),
                card("竞争格局", ind.path("competition").asText("")),
                card("全球共振", ind.path("globalResonance").asText("")))));

    // ② 公司
    sb.append("<h2>② 公司层面</h2>");
    JsonNode comp = analysis.path("company");
    sb.append(
        cardGrid(
            List.of(
                card("业务结构", comp.path("businessMix").asText("")),
                card("12季度业绩", comp.path("quarterly12").asText("")),
                card("未来2年驱动", comp.path("next2yDriver").asText("")),
                card("护城河", comp.path("moat").asText("")),
                card("政策契合度", comp.path("policyFit").asText("")),
                card("全球化", comp.path("globalization").asText("")),
                card("价格趋势", comp.path("priceTrend").asText("")),
                card("董事长画像", comp.path("chairman").asText("")),
                card("催化剂", comp.path("catalysts").asText("")))));

    // ③ 估值
    sb.append("<h2>③ 估值层面</h2>");
    JsonNode val = analysis.path("valuation");
    sb.append("<div class='meta-grid'>");
    sb.append(metaItem("公司类型", val.path("type").asText("-")));
    String verdictClass = verdictHtmlClass(val.path("verdict").asText(""));
    sb.append(
        metaItem(
            "综合判定",
            "<span class='"
                + verdictClass
                + "'>"
                + esc(val.path("verdict").asText("-"))
                + "</span>"));
    sb.append("</div>");
    JsonNode methods = val.path("methods");
    if (methods.isArray() && methods.size() > 0) {
      sb.append(
          "<table><thead><tr><th>估值方法</th><th>当前值</th><th>合理区间</th><th>结论</th></tr></thead><tbody>");
      for (JsonNode m : methods) {
        String vc = verdictHtmlClass(m.path("verdict").asText(""));
        sb.append("<tr><td>")
            .append(esc(m.path("name").asText("")))
            .append("</td>")
            .append("<td>")
            .append(esc(m.path("current").asText("")))
            .append("</td>")
            .append("<td>")
            .append(esc(m.path("reasonable").asText("")))
            .append("</td>")
            .append("<td class='")
            .append(vc)
            .append("'>")
            .append(esc(m.path("verdict").asText("")))
            .append("</td></tr>");
      }
      sb.append("</tbody></table>");
    }
    sb.append("<div class='meta-grid'>");
    sb.append(metaItem("2026目标价", val.path("target2026").asText("-")));
    sb.append(metaItem("2027目标价", val.path("target2027").asText("-")));
    sb.append("</div>");
    if (!val.path("reasoning").asText("").isEmpty()) {
      sb.append("<div class='card'><div class='card-label'>估值依据</div><div class='card-value'>")
          .append(esc(val.path("reasoning").asText("")))
          .append("</div></div>");
    }

    // ④ 技术
    sb.append("<h2>④ 技术层面</h2>");
    JsonNode tech = analysis.path("technical");
    sb.append(
        cardGrid(
            List.of(
                card("趋势线", tech.path("trendLine").asText("")),
                card("均线", tech.path("ma").asText("")),
                card("成交量", tech.path("volume").asText("")),
                card("MACD", tech.path("macd").asText("")))));
    if (!tech.path("verdict").asText("").isEmpty()) {
      sb.append("<div class='card'><strong>综合判定: </strong>")
          .append(esc(tech.path("verdict").asText("")))
          .append("</div>");
    }

    // ⑤ 资金
    sb.append("<h2>⑤ 资金层面</h2>");
    JsonNode cap = analysis.path("capital");
    sb.append(
        cardGrid(
            List.of(
                card("主力资金", cap.path("mainNetIn").asText("")),
                card("北向资金", cap.path("northbound").asText("")),
                card("龙虎榜", cap.path("dragonTiger").asText("")))));
    if (!cap.path("verdict").asText("").isEmpty()) {
      sb.append("<div class='card'><strong>综合判定: </strong>")
          .append(esc(cap.path("verdict").asText("")))
          .append("</div>");
    }

    // ⑥ 紫苏叶（融合个股分析）
    if (chainNode != null && !chainNode.isMissingNode() && chainNode.size() > 0) {
      sb.append("<h2>⑥ 紫苏叶 · 产业链定位</h2>");
      sb.append(
          cardGrid(
              List.of(
                  card("行业", chainNode.path("industry").asText("")),
                  card("位置", chainNode.path("layer").asText("")),
                  card("护城河类型", chainNode.path("moatType").asText("")),
                  card("拆解路径", chainNode.path("chainPath").asText("")))));
      // 竞争格局
      JsonNode competitionNode = chainNode.path("competition");
      if (competitionNode.size() > 0) {
        sb.append("<h3>全球竞争格局</h3>");
        sb.append(
            cardGrid(
                List.of(
                    card("全球玩家", competitionNode.path("globalPlayers").asText("")),
                    card("中国位置", competitionNode.path("chinesePosition").asText("")),
                    card("地缘优势", competitionNode.path("geographicAdvantage").asText("")))));
      }
    }

    // ⑦ 九维（融合个股分析）
    if (nineNode != null && !nineNode.isMissingNode() && nineNode.size() > 0) {
      sb.append("<h2>⑦ 高景气九维 · 财务摘要</h2>");
      JsonNode finNode = nineNode.path("financial");
      if (finNode.size() > 0) {
        sb.append("<table><tbody>");
        sb.append("<tr><th>报告期</th><td>")
            .append(esc(finNode.path("latestPeriod").asText("-")))
            .append("</td></tr>");
        sb.append("<tr><th>ROE</th><td>")
            .append(esc(finNode.path("roe").asText("-")))
            .append("</td></tr>");
        sb.append("<tr><th>毛利率</th><td>")
            .append(esc(finNode.path("grossMargin").asText("-")))
            .append("</td></tr>");
        sb.append("<tr><th>净利率</th><td>")
            .append(esc(finNode.path("netMargin").asText("-")))
            .append("</td></tr>");
        sb.append("<tr><th>净利YoY</th><td>")
            .append(esc(finNode.path("yoyNetProfit").asText("-")))
            .append("</td></tr>");
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
          .append(esc(catalystsNode.asText("")))
          .append("</div></div>");
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
      sb.append(
              "<div class='card' style='background:#fefce8;border-color:#fde68a;'><strong>一句话结论: </strong>")
          .append(esc(summaryNode.path("oneLiner").asText("")))
          .append("</div>");
    }

    sb.append("<div class='footer'>本报告由 AI + baostock 公开数据 + 紫苏叶/九维方法论生成 · 不构成投资建议 · 记录ID: ")
        .append(entity.getId())
        .append("</div>");
    sb.append("</body></html>");
    return sb.toString();
  }

  // ======== HTML 片段辅助 ========

  private String card(String label, String value) {
    if (value == null || value.isEmpty()) return "";
    return "<div class='card'><div class='card-label'>"
        + esc(label)
        + "</div><div class='card-value'>"
        + esc(value)
        + "</div></div>";
  }

  private String cardGrid(List<String> cards) {
    String inner = cards.stream().filter(c -> !c.isEmpty()).collect(Collectors.joining(""));
    if (inner.isEmpty()) return "";
    return "<div class='cards-grid'>" + inner + "</div>";
  }

  private String metaItem(String label, String value) {
    return "<div class='meta-item'><div class='meta-label'>"
        + esc(label)
        + "</div><div class='meta-value'>"
        + value
        + "</div></div>";
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

  private JsonNode readAnalysis(String resultJson) {
    try {
      return MAPPER.readTree(resultJson == null || resultJson.isBlank() ? "{}" : resultJson);
    } catch (Exception e) {
      return MAPPER.createObjectNode();
    }
  }

  private static final String STYLE =
      """
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
              """;
}
