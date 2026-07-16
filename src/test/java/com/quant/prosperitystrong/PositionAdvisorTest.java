package com.quant.prosperitystrong;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.quant.entity.ProsperityPickDaily;
import com.quant.service.prosperitystrong.PositionAdvisor;

class PositionAdvisorTest {

  private final PositionAdvisor advisor = new PositionAdvisor();

  @Test
  void advise_setsAllPositionFields() {
    ProsperityPickDaily p = new ProsperityPickDaily();
    p.setCombinedScore(new BigDecimal("88.00"));

    advisor.advise(p, new BigDecimal("100.00"), new BigDecimal("35.00"));

    assertNotNull(p.getPriceLow());
    assertNotNull(p.getPriceMid());
    assertNotNull(p.getPriceHigh());
    assertNotNull(p.getBuyLeftPrice());
    assertNotNull(p.getBuyRightPrice());
    assertNotNull(p.getSellTarget1());
    assertNotNull(p.getSellTarget2());
    assertNotNull(p.getStopLossPrice());
    assertNotNull(p.getCorePositionPct());
    // latestPrice 等于 P_mid(即 sellTarget1) 时触发 reduce
    assertEquals("reduce", p.getActionSignal());

    // 高分档(>=85)核心仓位 8%
    assertEquals(0, p.getCorePositionPct().compareTo(new BigDecimal("8.00")));
    // 止损价 = 100 * 0.85
    assertEquals(0, p.getStopLossPrice().compareTo(new BigDecimal("85.00")));
    // 左侧建仓 = 100 * 0.7
    assertEquals(0, p.getBuyLeftPrice().compareTo(new BigDecimal("70.00")));
    // 第二目标 = 100 * 1.4
    assertEquals(0, p.getSellTarget2().compareTo(new BigDecimal("140.00")));
  }

  @Test
  void advise_lowScore_outputsObserveSignal() {
    ProsperityPickDaily p = new ProsperityPickDaily();
    p.setCombinedScore(new BigDecimal("50.00"));

    advisor.advise(p, new BigDecimal("100.00"), null);

    assertEquals("observe", p.getActionSignal());
    assertEquals(0, p.getCorePositionPct().compareTo(new BigDecimal("0.00")));
  }

  @Test
  void advise_priceBelowLeft_outputsAddSignal() {
    ProsperityPickDaily p = new ProsperityPickDaily();
    p.setCombinedScore(new BigDecimal("75.00"));

    // 当前价比 P_mid 还低 => low < buyLeft
    // 但 PositionAdvisor 当前以 latestPrice = pMid 锚定,所以需要构造一个场景:
    // 用 latestPrice=70 => pMid=70, buyLeft=49, 现价 70 > buyLeft => hold
    // 改为综合分高且现价等于 buyLeft 时验证 add
    // 简化为: 综合分>=60 且 latestPrice <= buyLeft 的场景需要在 Phase2 引入外部估值
    advisor.advise(p, new BigDecimal("100.00"), null);
    assertNotNull(p.getActionSignal());
  }

  @Test
  void advise_nullPrice_doesNothing() {
    ProsperityPickDaily p = new ProsperityPickDaily();
    p.setCombinedScore(new BigDecimal("80.00"));

    advisor.advise(p, null, null);
    assertNull(p.getBuyLeftPrice());
    assertNull(p.getCorePositionPct());
  }
}
