package com.quant.service.monitor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.quant.entity.InvestPositionCommon;

class MonitorAlertTemplateTest {

  @Test
  void rendersStandardTemplateWithStockNameAndPrice() {
    InvestPositionCommon pos = new InvestPositionCommon();
    pos.setStockCode("600519.SH");

    MonitorSignal sig =
        MonitorSignal.fixedPriceBuy(
            pos, "600519.SH", "贵州茅台", new BigDecimal("1500.00"), new BigDecimal("1480.00"));
    // 默认 null template 时走 standard 分支
    String md = MonitorAlertTemplate.render(sig);

    assertTrue(md.contains("贵州茅台"), "should contain stock name: " + md);
    assertTrue(md.contains("1500"), "should contain triggerPrice: " + md);
    assertTrue(md.contains("固定买入价"), "should mention fixedBuy label");
  }

  @Test
  void rendersCompactTemplate() {
    InvestPositionCommon pos = new InvestPositionCommon();
    pos.setServerchanTemplate("compact");

    MonitorSignal sig =
        MonitorSignal.fixedPriceSell(
            pos, "000001.SZ", "平安银行", new BigDecimal("12.50"), new BigDecimal("13.00"));

    String md = MonitorAlertTemplate.render(sig);
    assertTrue(md.contains("平安银行"));
    assertTrue(md.contains("12.50"));
    assertTrue(md.length() < 200, "compact should be short: len=" + md.length());
  }

  @Test
  void rendersVerboseTemplate() {
    InvestPositionCommon pos = new InvestPositionCommon();
    pos.setServerchanTemplate("verbose");

    MonitorSignal sig =
        MonitorSignal.takeProfit(
            pos,
            "300750.SZ",
            "宁德时代",
            new BigDecimal("250.00"),
            new BigDecimal("100.00"),
            new BigDecimal("20.00"));

    String md = MonitorAlertTemplate.render(sig);
    assertTrue(md.contains("信号类型"));
    assertTrue(md.contains("take_profit_hit"));
  }
}
