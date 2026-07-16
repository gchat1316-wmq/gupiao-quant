package com.quant.service.prosperitystrong;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.entity.InvestProsperityPick;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds the SenseNova image-generation prompt for the analysis-summary infographic. Falls back to
 * the AI-generated {@code summary.infographicPrompt} if present, otherwise composes a soft-pink /
 * yellow / blue cute cartoon poster prompt from the bullets + oneLiner.
 */
@Slf4j
@Component
public class ProsperityPickInfographicPromptBuilder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public String buildImagePromptFromResult(InvestProsperityPick entity) {
    try {
      JsonNode root =
          MAPPER.readTree(entity.getResultJson() == null ? "{}" : entity.getResultJson());
      JsonNode summary = root.path("summary");
      String oneLiner = summary.path("oneLiner").asText("");
      JsonNode bullets = summary.path("bullets");
      String existing = summary.path("infographicPrompt").asText("");
      if (!existing.isBlank()) return existing;

      StringBuilder bul = new StringBuilder();
      if (bullets.isArray()) {
        int i = 0;
        for (JsonNode b : bullets) {
          bul.append((char) ('①' + i)).append(' ').append(b.asText()).append("；");
          i++;
          if (i >= 6) break;
        }
      }
      return "请生成一张以柔和粉色、淡黄色和浅蓝色为主色调的可爱卡通风格信息图（含猫咪、拟人化表情等元素），"
          + "主题为「"
          + entity.getStockName()
          + " "
          + entity.getStockCode()
          + " 景气度选股六维分析摘要」，"
          + "整体排版从左到右分为三个区块：①行业景气度  ②公司基本面与估值  ③技术与资金面。"
          + "请用图标 + 短句形式呈现以下要点："
          + bul
          + "结论一句话："
          + oneLiner
          + "。包含醒目的主标题与副标题，整体设计有亲和力，信息密度高。";
    } catch (Exception e) {
      return "请生成一张可爱卡通风格信息图，主题为 " + entity.getStockName() + " 景气度选股摘要。";
    }
  }
}
