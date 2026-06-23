package com.quant.dto.practicalselect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 实战选股综合响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticalSelectResponse {

    /** 是否匹配到股票 */
    private boolean matched;

    /** 错误信息（未匹配时填充） */
    private String message;

    /** 数据库记录 ID（保存历史后填充，供前端做分享/删除/PDF） */
    private Long recordId;

    private String stockCode;
    private String stockName;

    /** 当前价（最近交易日收盘） */
    private Double currentPrice;

    /** 走势分析（完美走势） */
    private TrendAnalysis trend;

    /** 漂亮数字 / 16 季度财务分析 */
    private FinancialAnalysis financials;

    /** 稀缺性 + 成长动力星级评级（来自 LLM） */
    private StarRating rating;

    /** 估值分析（PS 法 + 计算过程） */
    private ValuationAnalysis valuation;

    /** 综合一句话结论（卡片顶用） */
    private String summaryHeadline;

    /** 数据来源说明 */
    private String dataNote;
}