package com.quant.dto.practicalselect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 稀缺性 + 成长动力星级评级（来自 LLM）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StarRating {

    /** 稀缺性综合评级（5 分制） */
    private Double scarcityStars;

    /** 稀缺性星级文本 */
    private String scarcityStarsText;

    /** 稀缺性一句话总结 */
    private String scarcitySummary;

    /** 稀缺性子维度评分 */
    private List<DimensionRating> scarcityDimensions;

    /** 成长动力综合评级（5 分制） */
    private Double growthStars;

    /** 成长动力星级文本 */
    private String growthStarsText;

    /** 成长动力一句话总结 */
    private String growthSummary;

    /** 成长动力子维度评分 */
    private List<DimensionRating> growthDimensions;

    /** 成长动力短板（降星原因） */
    private List<String> growthWeaknesses;

    /** 是否为 AI 生成（true=AI；false=本地 fallback） */
    private boolean aiGenerated;

    /** AI 原始响应（仅 fallback 时使用） */
    private String rawAiResponse;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionRating {
        private String name;      // 如 "技术稀缺"
        private Double stars;     // 0-5
        private String reason;    // 一句话打分理由
    }
}