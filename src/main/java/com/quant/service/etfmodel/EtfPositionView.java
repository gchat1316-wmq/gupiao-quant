package com.quant.service.etfmodel;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.quant.entity.EtfPool;

import lombok.Builder;
import lombok.Data;

/** 由交易流水计算出的单支 ETF 持仓快照（摊薄成本口径），供规则引擎与前端使用。 */
@Data
@Builder
public class EtfPositionView {

  private Long poolId;
  private String stockCode;
  private String stockName;
  /** BROAD | SECTOR */
  private String category;

  /** 当前持有份额 */
  private int shares;

  /** 净投入 = 累计买入金额 - 累计卖出金额（可为负 = 已锁定利润） */
  private BigDecimal netInvested;

  /** 摊薄成本价 = 净投入 / 份额；净投入 ≤0 或空仓时为 null */
  private BigDecimal dilutedCost;

  /** 已用建仓/加仓批次数（OPEN + ADD，不含做T/回补） */
  private int batchesUsed;

  private boolean tp1Done;
  private boolean tp2Done;
  private boolean sl1Done;
  private boolean sl2Done;

  private String recoupStatus;

  public boolean isBroad() {
    return EtfPool.CATEGORY_BROAD.equals(category);
  }

  /** 相对摊薄成本的收益率(%)；成本不可算时返回 null。 */
  public BigDecimal profitPct(BigDecimal latestPrice) {
    if (latestPrice == null
        || shares <= 0
        || dilutedCost == null
        || dilutedCost.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return latestPrice
        .subtract(dilutedCost)
        .divide(dilutedCost, 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP);
  }
}
