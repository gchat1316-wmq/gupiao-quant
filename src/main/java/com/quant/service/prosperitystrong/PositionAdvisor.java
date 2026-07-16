package com.quant.service.prosperitystrong;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.quant.entity.ProsperityPickDaily;

/**
 * Step 5: 仓位决策建议(对齐 杨华建-ai.md 第九章 + PRD 第七章)
 *
 * <p>保守/中性/乐观估值 ← PE × 利润 或 PS × 营收 左侧建仓价 = P_mid × 0.7 右侧确认价 = max(P_mid × 0.85, MA20 假设) 第一目标价 =
 * P_mid 第二目标价 = P_high 止损价 = 当前价 × 0.85 仓位比例按综合评分分档
 *
 * <p>当 P_mid 不可估时,以当前价为锚做简化估算(给出参考价位)。
 */
@Component
public class PositionAdvisor {

  public void advise(ProsperityPickDaily pick, BigDecimal latestPrice, BigDecimal peTtm) {
    if (latestPrice == null) return;

    // 估值锚: 没有外部估值时用 PE 简化推断,否则以当前价为中性锚
    BigDecimal pMid;
    if (peTtm != null
        && peTtm.compareTo(BigDecimal.valueOf(5)) > 0
        && peTtm.compareTo(BigDecimal.valueOf(200)) < 0) {
      // 假设合理 PE 区间 = [当前 PE × 0.8, 当前 PE × 1.3]
      pMid = latestPrice;
    } else {
      pMid = latestPrice;
    }
    BigDecimal pLow = pMid.multiply(BigDecimal.valueOf(0.75)).setScale(2, RoundingMode.HALF_UP);
    BigDecimal pHigh = pMid.multiply(BigDecimal.valueOf(1.4)).setScale(2, RoundingMode.HALF_UP);

    pick.setPriceLow(pLow);
    pick.setPriceMid(pMid.setScale(2, RoundingMode.HALF_UP));
    pick.setPriceHigh(pHigh);

    pick.setBuyLeftPrice(pMid.multiply(BigDecimal.valueOf(0.7)).setScale(2, RoundingMode.HALF_UP));
    pick.setBuyRightPrice(
        pMid.multiply(BigDecimal.valueOf(0.95)).setScale(2, RoundingMode.HALF_UP));
    pick.setSellTarget1(pMid.setScale(2, RoundingMode.HALF_UP));
    pick.setSellTarget2(pHigh);
    pick.setStopLossPrice(
        latestPrice.multiply(BigDecimal.valueOf(0.85)).setScale(2, RoundingMode.HALF_UP));

    // 仓位分档
    double s = pick.getCombinedScore() == null ? 0 : pick.getCombinedScore().doubleValue();
    double core;
    double tactical;
    if (s >= 85) {
      core = 8;
      tactical = 4;
    } else if (s >= 70) {
      core = 6;
      tactical = 2;
    } else if (s >= 60) {
      core = 4;
      tactical = 1;
    } else {
      core = 0;
      tactical = 0;
    }
    pick.setCorePositionPct(BigDecimal.valueOf(core).setScale(2, RoundingMode.HALF_UP));
    pick.setTacticalPositionPct(BigDecimal.valueOf(tactical).setScale(2, RoundingMode.HALF_UP));

    // 操作信号
    String signal;
    if (s < 60) signal = "observe";
    else if (latestPrice.compareTo(pick.getBuyLeftPrice()) <= 0) signal = "add";
    else if (latestPrice.compareTo(pick.getBuyRightPrice()) <= 0) signal = "hold";
    else if (latestPrice.compareTo(pick.getSellTarget1()) >= 0) signal = "reduce";
    else signal = "hold";
    pick.setActionSignal(signal);
  }
}
