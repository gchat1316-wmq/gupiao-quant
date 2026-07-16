package com.quant.dto.position;

import java.math.BigDecimal;
import java.util.List;

import lombok.Builder;
import lombok.Data;

/** 仓位建议响应:封装三大公式的完整输出。 */
@Data
@Builder
public class PositionAdviceDTO {

  /** 输入摘要,便于前端回显 */
  private InputSummary input;

  /** 仓位公式输出 */
  private Sizing sizing;

  /** 风险回报比输出 */
  private RiskReward riskReward;

  /** 期望值输出 */
  private Expectation expectation;

  /** 连续亏损场景回撤表 */
  private List<DrawdownRow> drawdownTable;

  /** 结论与建议 */
  private String verdict;

  private String summary;
  private List<String> principles;

  @Data
  @Builder
  public static class InputSummary {
    private String stockKeyword;
    private BigDecimal accountCapital;
    private BigDecimal entryPrice;
    private BigDecimal stopLossPrice;
    private BigDecimal targetPrice;
    private BigDecimal riskPercent;
    private BigDecimal winRate;
  }

  @Data
  @Builder
  public static class Sizing {
    /** 入场到止损的每股风险 */
    private BigDecimal riskPerShare;

    /** 单笔交易最大可承受亏损(元) */
    private BigDecimal maxRiskAmount;

    /** 建议可买入股数 */
    private BigDecimal shares;

    /** 买入占用资金 */
    private BigDecimal positionAmount;

    /** 占账户比例 */
    private BigDecimal positionPct;

    /** A股最小交易单位 100 股对应的实际可买股数 */
    private BigDecimal lotsOf100;
  }

  @Data
  @Builder
  public static class RiskReward {
    /** 每股潜在盈利 */
    private BigDecimal rewardPerShare;

    /** 风险:回报 (e.g. 1:3) */
    private String ratioLabel;

    /** 风险:回报数值形式 reward/risk */
    private BigDecimal ratioValue;

    /** 该风报比下盈亏平衡所需的最低胜率(小数) */
    private BigDecimal breakEvenWinRate;

    /** 评价:excellent/good/marginal/poor */
    private String verdict;

    private String verdictReason;
  }

  @Data
  @Builder
  public static class Expectation {
    /** 用户胜率(0-1) */
    private BigDecimal winRate;

    /** 平均盈利(元) */
    private BigDecimal avgWin;

    /** 平均亏损(元) */
    private BigDecimal avgLoss;

    /** 单笔期望收益(元) */
    private BigDecimal expectedValuePerTrade;

    /** 100 笔累计预期收益(元) */
    private BigDecimal expectedValue100Trades;

    /** 评价:positive/negative/breakeven */
    private String verdict;
  }

  @Data
  @Builder
  public static class DrawdownRow {
    /** 连续亏损次数 */
    private int consecutiveLosses;

    /** 账户剩余比例 */
    private BigDecimal remainingPct;

    /** 回撤后恢复所需盈利率 */
    private BigDecimal recoverGainRequired;
  }
}
