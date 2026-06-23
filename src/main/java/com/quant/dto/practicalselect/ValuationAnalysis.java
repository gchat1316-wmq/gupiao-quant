package com.quant.dto.practicalselect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 估值分析（基于 PS 法 + 净利润率自适应）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValuationAnalysis {

    /** 估值方法名（"10 倍 PS" / "6 倍 PS" / "4 倍 PS" / "PE 匹配法"） */
    private String method;

    /** 估值方法选择原因（"净利润率 15% → 6 倍 PS，对应净利率 ×25% 基准打折"） */
    private String methodReason;

    /** 当前市值（亿元） */
    private Double currentMarketCapYi;

    /** 当前股价（元） */
    private Double currentPrice;

    /** 总股本（亿股） */
    private Double totalSharesYi;

    /** 最近一期净利率 % */
    private Double latestNetMargin;

    /** 估值倍数（PS） */
    private Double psMultiple;

    /** 今年预测营收（亿元） */
    private Double forecastRevenueY0;

    /** 明年预测营收（亿元） */
    private Double forecastRevenueY1;

    /** 后年预测营收（亿元） */
    private Double forecastRevenueY2;

    /** 合理市值（亿元） = 明年预测营收 × PS 倍数 */
    private Double fairMarketCapYi;

    /** 估值判定：低估 / 合理 / 高估 */
    private String verdict;

    /** 估值评语 */
    private String commentary;

    /** 最近大阳线建仓建议文本（可选） */
    private String buildPositionTip;
}