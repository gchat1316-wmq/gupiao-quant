package com.quant.dto.practicalselect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * "漂亮数字" —— 16 季度财务分析。
 *
 * 数据源：trade_stock_financial。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialAnalysis {

    /** 一句话总结 */
    private String summary;

    /** 最近 16 季度关键指标 */
    private List<QuarterSnapshot> quarters;

    /** SOP 三大数字体检判定：pass / warn / fail */
    private String sopVerdict;

    /** SOP 三大数字体检一句话总结 */
    private String sopSummary;

    /** SOP 三项明细（毛利率 / 营收同比 / 扣非同比），供前端展示分项判定 */
    private List<SopMetricBrief> sopMetrics;

    /** 营收同比趋势（最近 8 季度） */
    private List<Double> revenueYoySeries;

    /** 扣非净利润同比趋势（最近 8 季度） */
    private List<Double> profitYoySeries;

    /** 毛利率趋势（最近 8 季度） */
    private List<Double> grossMarginSeries;

    /** 最近一期毛利率 % */
    private Double latestGrossMargin;

    /** 最近一期净利率 % */
    private Double latestNetMargin;

    /** 最近一期营收同比 % */
    private Double latestRevenueYoy;

    /** 最近一期扣非同比 % */
    private Double latestProfitYoy;

    /** 业绩复苏判定：true=最近一期营收由负转正 */
    private boolean turnaroundDetected;

    /** 业绩复苏说明 */
    private String turnaroundNote;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuarterSnapshot {
        private String quarter;     // 25Q1
        private String reportDate;  // 2025-03-31
        private Double revenueYi;   // 亿元（单季）
        private Double revenueYoy;  // 同比 %
        private Double netMargin;   // 净利率 %
        private Double grossMargin; // 毛利率 %
        private Double eps;
        private Double roe;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SopMetricBrief {
        /** 指标名：毛利率 / 营收同比 / 扣非净利润同比 */
        private String label;
        /** 判定：pass / warn / fail */
        private String verdict;
        /** 数值文本（含符号和单位，如 "+3.75%"） */
        private String latestText;
        /** 一句话解释 */
        private String tip;
    }
}