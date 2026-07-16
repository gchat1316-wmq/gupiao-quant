package com.quant.dto.practicalselect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 估值分析（统一 10 倍 PS 法）。
 *
 * <p>适用条件：净利润率 ≥ 25% 的高科技公司。
 *
 * <p>公式：合理市值 = 未来一年预测营收 × 10
 *
 * <p>判定：低估（市值 &lt; Y1×10）/ 合理（Y1×10 ≤ 市值 ≤ Y2×10）/ 泡沫（市值 &gt; Y2×10）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValuationAnalysis {

  /** 估值方法名（固定为 "10 倍 PS 法"） */
  private String method;

  /** 估值方法说明（净利率 ≥ 25% 则适用，否则提示不适用） */
  private String methodReason;

  /** 当前市值（亿元） */
  private Double currentMarketCapYi;

  /** 当前股价（元） */
  private Double currentPrice;

  /** 总股本（亿股） */
  private Double totalSharesYi;

  /** 最近一期净利率 % */
  private Double latestNetMargin;

  /** 估值倍数（固定 10 倍） */
  private Double psMultiple;

  /** TTM 营收（亿元） */
  private Double forecastRevenueY0;

  /** Y1 预测营收（亿元） */
  private Double forecastRevenueY1;

  /** Y2 预测营收（亿元） */
  private Double forecastRevenueY2;

  /** Y1×10 合理市值（亿元） */
  private Double fairCapY1Yi;

  /** Y2×10 合理市值（亿元） */
  private Double fairCapY2Yi;

  /** 估值判定：低估 / 合理 / 泡沫 / — */
  private String verdict;

  /** 估值评语 */
  private String commentary;

  /** 最近大阳线建仓建议文本（可选） */
  private String buildPositionTip;
}
