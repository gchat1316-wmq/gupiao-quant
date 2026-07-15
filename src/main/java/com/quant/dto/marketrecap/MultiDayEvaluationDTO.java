package com.quant.dto.marketrecap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 多日强弱评估（排除一日游，抓可持续主线）
 * 评估维度：涨停持续性(30%)、资金持续性(20%)、连板递进(20%)、抗跌性(15%)、催化持续性(15%)
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiDayEvaluationDTO {

    /** 评估维度得分说明 */
    private String dimensions;

    /** 各概念评分列表 */
    private List<ConceptScore> concepts;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConceptScore {
        /** 概念名称，如"医药/创新药" */
        private String name;
        /** 总分 */
        private Double totalScore;
        /** 判断结论：主线/观察期/一日游 */
        private String label;
        /** 各维度得分 */
        private Scores scores;
        /** 详细记录（含日期+公司） */
        private List<String> detail;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scores {
        private Double limitUpPersistence;    // 涨停持续性 30%
        private Double capitalPersistence;     // 资金持续性 20%
        private Double boardProgression;       // 连板递进 20%
        private Double resilience;             // 抗跌性 15%
        private Double catalystPersistence;    // 催化持续性 15%
    }
}
