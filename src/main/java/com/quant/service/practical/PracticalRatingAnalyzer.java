package com.quant.service.practical;

import static com.quant.service.practical.PracticalSelectSupport.extractJsonBlock;
import static com.quant.service.practical.PracticalSelectSupport.starsToText;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.config.AiProperties;
import com.quant.dto.practicalselect.FinancialAnalysis;
import com.quant.dto.practicalselect.StarRating;
import com.quant.dto.practicalselect.TrendAnalysis;
import com.quant.dto.practicalselect.ValuationAnalysis;
import com.quant.entity.TradeStockBasic;
import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 实战选股 · 星级评级（MiniMax 优先 → SenseNova 兜底 → 本地启发式 fallback）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PracticalRatingAnalyzer {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final MiniMaxClient miniMaxClient;
  private final SenseNovaClient senseNovaClient;
  private final AiProperties aiProperties;

  public StarRating buildRating(
      TradeStockBasic basic, FinancialAnalysis fin, ValuationAnalysis val, TrendAnalysis trend) {
    String sys = STAR_SYSTEM_PROMPT;
    String user = buildRatingUserPrompt(basic, fin, val, trend);
    String lastErr = null;

    // 1) MiniMax 优先
    if (aiProperties.getMinimax().isEnabled()) {
      try {
        String raw = miniMaxClient.chatComplete(sys, user);
        StarRating parsed = parseRatingJson(raw);
        if (parsed != null && parsed.getScarcityStars() != null) {
          parsed.setAiGenerated(true);
          parsed.setRawAiResponse(raw);
          return parsed;
        }
        lastErr = "MiniMax 响应解析失败";
      } catch (Exception e) {
        lastErr = "MiniMax: " + e.getMessage();
        log.warn("MiniMax 评级失败，尝试 SenseNova 兜底: {}", e.getMessage());
      }
    }

    // 2) SenseNova 兜底
    if (aiProperties.getSensenova() != null && aiProperties.getSensenova().isEnabled()) {
      try {
        String raw = senseNovaClient.chatComplete(sys, user);
        StarRating parsed = parseRatingJson(raw);
        if (parsed != null && parsed.getScarcityStars() != null) {
          parsed.setAiGenerated(true);
          parsed.setRawAiResponse(raw);
          return parsed;
        }
        lastErr = "SenseNova 响应解析失败";
      } catch (Exception e) {
        lastErr = (lastErr == null ? "" : lastErr + "; ") + "SenseNova: " + e.getMessage();
        log.warn("SenseNova 评级也失败，使用本地 fallback: {}", e.getMessage());
      }
    }

    return fallbackRating(basic, fin, val, lastErr == null ? "AI 未启用" : lastErr);
  }

  private StarRating parseRatingJson(String raw) {
    try {
      // 提取 JSON 块（容忍 ```json ``` 包裹或前后文本）
      String json = extractJsonBlock(raw);
      if (json == null) return null;
      JsonNode root = MAPPER.readTree(json);

      StarRating.StarRatingBuilder b = StarRating.builder();

      JsonNode sc = root.path("scarcity");
      if (sc.isObject()) {
        b.scarcityStars(getDoubleOrNull(sc.path("stars")))
            .scarcityStarsText(
                sc.path("starsText").asText(starsToText(getDoubleOrNull(sc.path("stars")))))
            .scarcitySummary(sc.path("summary").asText(""))
            .scarcityDimensions(parseDimensions(sc.path("dimensions")));
      }

      JsonNode gr = root.path("growth");
      if (gr.isObject()) {
        b.growthStars(getDoubleOrNull(gr.path("stars")))
            .growthStarsText(
                gr.path("starsText").asText(starsToText(getDoubleOrNull(gr.path("stars")))))
            .growthSummary(gr.path("summary").asText(""))
            .growthDimensions(parseDimensions(gr.path("dimensions")));

        List<String> weaknesses = new ArrayList<>();
        gr.path("weaknesses").forEach(n -> weaknesses.add(n.asText()));
        b.growthWeaknesses(weaknesses);
      }
      return b.build();
    } catch (Exception e) {
      log.warn("解析 AI 星级 JSON 失败: {}", e.getMessage());
      return null;
    }
  }

  private List<StarRating.DimensionRating> parseDimensions(JsonNode arr) {
    List<StarRating.DimensionRating> out = new ArrayList<>();
    if (arr.isArray()) {
      arr.forEach(
          n ->
              out.add(
                  StarRating.DimensionRating.builder()
                      .name(n.path("name").asText(""))
                      .stars(getDoubleOrNull(n.path("stars")))
                      .reason(n.path("reason").asText(""))
                      .build()));
    }
    return out;
  }

  private Double getDoubleOrNull(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) return null;
    if (n.isNumber()) return n.asDouble();
    try {
      return Double.parseDouble(n.asText());
    } catch (Exception e) {
      return null;
    }
  }

  private StarRating fallbackRating(
      TradeStockBasic basic, FinancialAnalysis fin, ValuationAnalysis val, String reason) {
    // 本地启发式打分（AI 失败时使用）
    // 稀缺性：估值低估 + 业绩拐点 + 高毛利 → 4-5 星
    double scarcity = 0;
    if (val != null && "低估".equals(val.getVerdict())) scarcity += 2;
    else if (val != null && "合理".equals(val.getVerdict())) scarcity += 1;
    if (fin != null && fin.isTurnaroundDetected()) scarcity += 1;
    if (fin != null && fin.getLatestGrossMargin() != null && fin.getLatestGrossMargin() >= 40)
      scarcity += 1;
    if (fin != null && "pass".equals(fin.getSopVerdict())) scarcity += 1;
    scarcity = Math.min(5, scarcity);

    // 成长动力：营收同比 > 20% → +2，> 10% → +1；扣非 > 营收 → +1；ROE > 15 → +1
    double growth = 0;
    if (fin != null && fin.getLatestRevenueYoy() != null) {
      double yoy = fin.getLatestRevenueYoy();
      if (yoy >= 30) growth += 2;
      else if (yoy >= 10) growth += 1;
    }
    if (fin != null
        && fin.getLatestProfitYoy() != null
        && fin.getLatestRevenueYoy() != null
        && fin.getLatestProfitYoy() > fin.getLatestRevenueYoy()) {
      growth += 1;
    }
    if (fin != null && fin.getQuarters() != null && !fin.getQuarters().isEmpty()) {
      Double roe = fin.getQuarters().get(fin.getQuarters().size() - 1).getRoe();
      if (roe != null && roe >= 15) growth += 1;
    }
    if (fin != null && fin.isTurnaroundDetected()) growth += 1;
    growth = Math.min(5, growth);

    return StarRating.builder()
        .scarcityStars(scarcity)
        .scarcityStarsText(starsToText(scarcity))
        .scarcitySummary("(本地启发式评分 - AI 不可用: " + reason + ")")
        .scarcityDimensions(
            List.of(
                StarRating.DimensionRating.builder()
                    .name("估值水平")
                    .stars(val != null && "低估".equals(val.getVerdict()) ? 5.0 : 3.0)
                    .reason("基于 PS 估值结果自动评分")
                    .build(),
                StarRating.DimensionRating.builder()
                    .name("财务质量")
                    .stars(
                        fin != null && "pass".equals(fin.getSopVerdict())
                            ? 5.0
                            : "warn".equals(fin != null ? fin.getSopVerdict() : "") ? 3.0 : 2.0)
                    .reason("基于 SOP 体检结果自动评分")
                    .build(),
                StarRating.DimensionRating.builder()
                    .name("业绩拐点")
                    .stars(fin != null && fin.isTurnaroundDetected() ? 5.0 : 3.0)
                    .reason("营收是否由负转正")
                    .build()))
        .growthStars(growth)
        .growthStarsText(starsToText(growth))
        .growthSummary("(本地启发式评分)")
        .growthDimensions(
            List.of(
                StarRating.DimensionRating.builder()
                    .name("营收增速")
                    .stars(
                        fin != null
                                && fin.getLatestRevenueYoy() != null
                                && fin.getLatestRevenueYoy() >= 30
                            ? 5.0
                            : 3.0)
                    .reason("基于最近一期营收同比")
                    .build(),
                StarRating.DimensionRating.builder()
                    .name("盈利质量")
                    .stars(
                        fin != null
                                && fin.getLatestProfitYoy() != null
                                && fin.getLatestRevenueYoy() != null
                                && fin.getLatestProfitYoy() > fin.getLatestRevenueYoy()
                            ? 5.0
                            : 3.0)
                    .reason("扣非 vs 营收增速差")
                    .build()))
        .aiGenerated(false)
        .rawAiResponse(reason)
        .build();
  }

  private String buildRatingUserPrompt(
      TradeStockBasic basic, FinancialAnalysis fin, ValuationAnalysis val, TrendAnalysis trend) {
    StringBuilder sb = new StringBuilder();
    sb.append("# 公司基本信息\n");
    sb.append("- 股票代码：").append(basic.getStockCode()).append("\n");
    sb.append("- 公司简称：").append(basic.getStockName()).append("\n");
    sb.append("- 行业：")
        .append(basic.getSectorNames() == null ? "—" : basic.getSectorNames())
        .append("\n\n");

    sb.append("# 估值快照\n");
    if (val != null) {
      sb.append("- 估值方法：").append(val.getMethod()).append("\n");
      sb.append("- 当前市值：")
          .append(val.getCurrentMarketCapYi() == null ? "—" : val.getCurrentMarketCapYi() + " 亿")
          .append("\n");
      sb.append("- Y1×10 合理市值：")
          .append(val.getFairCapY1Yi() == null ? "—" : val.getFairCapY1Yi() + " 亿")
          .append("\n");
      sb.append("- Y2×10 合理市值：")
          .append(val.getFairCapY2Yi() == null ? "—" : val.getFairCapY2Yi() + " 亿")
          .append("\n");
      sb.append("- 估值结论：").append(val.getVerdict()).append("\n");
      sb.append("- PS 倍数依据：").append(val.getMethodReason()).append("\n\n");
    }

    sb.append("# 财务快照\n");
    if (fin != null && fin.getQuarters() != null && !fin.getQuarters().isEmpty()) {
      FinancialAnalysis.QuarterSnapshot latest =
          fin.getQuarters().get(fin.getQuarters().size() - 1);
      sb.append("- 最新季度：").append(latest.getQuarter()).append("\n");
      sb.append("- 营收同比：")
          .append(latest.getRevenueYoy() == null ? "—" : latest.getRevenueYoy() + "%")
          .append("\n");
      sb.append("- 毛利率：")
          .append(latest.getGrossMargin() == null ? "—" : latest.getGrossMargin() + "%")
          .append("\n");
      sb.append("- 净利率：")
          .append(latest.getNetMargin() == null ? "—" : latest.getNetMargin() + "%")
          .append("\n");
      sb.append("- ROE：")
          .append(latest.getRoe() == null ? "—" : latest.getRoe() + "%")
          .append("\n");
      sb.append("- 业绩复苏：").append(fin.isTurnaroundDetected() ? "是" : "否").append("\n");
      sb.append("- SOP 体检：")
          .append(fin.getSopVerdict())
          .append(" / ")
          .append(fin.getSopSummary())
          .append("\n\n");
    }

    sb.append("# 走势快照\n");
    if (trend != null) {
      sb.append("- 突破平台：").append(trend.isBreakoutDetected() ? "是" : "否").append("\n");
      sb.append("- 本月至今：")
          .append(
              trend.getMonthToDateReturnPct() == null ? "—" : trend.getMonthToDateReturnPct() + "%")
          .append("\n");
      sb.append("- 最近大阳线：")
          .append(
              trend.getRecentBigYang() == null || trend.getRecentBigYang().isEmpty()
                  ? "无"
                  : trend.getRecentBigYang().size() + " 根")
          .append("\n\n");
    }

    sb.append(RATING_TASK_TEMPLATE);
    return sb.toString();
  }

  private static final String RATING_TASK_TEMPLATE =
      """
      # 任务
      请基于以上数据，按 A 股实战选股框架对该公司做稀缺性和成长动力综合评级，输出严格 JSON。

      ## 输出格式（严格按此 JSON 结构，不要任何额外文字）
      ```
      {
        "scarcity": {
          "stars": 4.5,
          "starsText": "★★★★☆",
          "summary": "一段话总结稀缺性（80 字内）",
          "dimensions": [
            {"name": "技术稀缺", "stars": 5.0, "reason": "一句话理由"},
            {"name": "客户资质", "stars": 5.0, "reason": "一句话理由"},
            {"name": "商业模式", "stars": 4.5, "reason": "一句话理由"}
          ]
        },
        "growth": {
          "stars": 4.0,
          "starsText": "★★★★☆",
          "summary": "一段话总结成长动力（80 字内）",
          "dimensions": [
            {"name": "行业景气", "stars": 5.0, "reason": "一句话"},
            {"name": "产能落地", "stars": 5.0, "reason": "一句话"},
            {"name": "基本盘", "stars": 4.0, "reason": "一句话"},
            {"name": "第三曲线", "stars": 3.0, "reason": "一句话"}
          ],
          "weaknesses": [
            "降星原因 1",
            "降星原因 2"
          ]
        }
      }
      ```

      ## 评分准则
      - 稀缺性：技术壁垒 + 客户资源 + 商业模式 + A 股独特性，0-5 星
      - 成长动力：行业景气 + 产能落地 + 基本盘 + 第三曲线 + 短板扣分，0-5 星
      - 维度打分请保留 1 位小数
      - 文字简洁，每条理由 ≤ 30 字
      """;

  private static final String STAR_SYSTEM_PROMPT =
      """
            你是 A 股实战选股分析师，熟悉龙江投资体系。你的任务是基于提供的财务 + 估值 + 走势数据，给出稀缺性和成长动力的星级评级。
            必须严格按照 JSON 输出，不要包含任何 JSON 之外的文字、解释、Markdown 包裹。
            维度评分保留 1 位小数，文字简洁客观，避免主观吹捧。
            """;
}
