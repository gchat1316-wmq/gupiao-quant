package com.quant.service.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.quant.entity.InvestPositionCommon;

class MonitorRuleEngineTest {

  private InvestPositionCommon pos;
  private MonitorRuleEngine engine;

  @BeforeEach
  void setUp() {
    engine = new MonitorRuleEngine();
    pos = new InvestPositionCommon();
    pos.setStockCode("600519.SH");
    pos.setPoolType("tech_ai");
  }

  private MonitorContext ctx(BigDecimal latest, BigDecimal openToday, BigDecimal atr) {
    return MonitorContext.builder()
        .position(pos)
        .stockCode("600519.SH")
        .stockName("贵州茅台")
        .latest(latest)
        .openToday(openToday)
        .prevClose(new BigDecimal("1480.00"))
        .atr(atr)
        .quoteTime(LocalDateTime.now())
        .build();
  }

  @Test
  void fixedBuyTriggersWhenLatestBelowThreshold() {
    pos.setFixedBuyPrice(new BigDecimal("1500.00"));
    pos.setFixedBuyEnabled(1);

    List<MonitorSignal> sigs =
        engine.evaluate(
            ctx(new BigDecimal("1480.00"), new BigDecimal("1490.00"), new BigDecimal("10.00")));

    assertEquals(1, sigs.size());
    assertEquals(MonitorSignal.FIXED_BUY, sigs.get(0).getSignalType());
    assertTrue(sigs.get(0).getTitle().contains("贵州茅台"));
  }

  @Test
  void fixedBuyDisabledSuppressesTrigger() {
    pos.setFixedBuyPrice(new BigDecimal("1500.00"));
    pos.setFixedBuyEnabled(0);

    List<MonitorSignal> sigs =
        engine.evaluate(
            ctx(new BigDecimal("1480.00"), new BigDecimal("1490.00"), new BigDecimal("10.00")));

    assertTrue(sigs.isEmpty());
  }

  @Test
  void fixedSellTriggersWhenLatestAboveThreshold() {
    pos.setFixedSellPrice(new BigDecimal("1500.00"));
    pos.setFixedSellEnabled(1);

    List<MonitorSignal> sigs =
        engine.evaluate(
            ctx(new BigDecimal("1520.00"), new BigDecimal("1500.00"), new BigDecimal("10.00")));

    assertEquals(1, sigs.size());
    assertEquals(MonitorSignal.FIXED_SELL, sigs.get(0).getSignalType());
  }

  @Test
  void atrAmplitudeTriggersWhenMoveExceedsMultiplier() {
    pos.setAtrAlertAmplitude(new BigDecimal("1.500"));
    pos.setAtrAlertEnabled(1);

    // |12.00 - 11.00| = 1.00; threshold = 1.5 * 0.50 = 0.75 → 触发
    List<MonitorSignal> sigs =
        engine.evaluate(
            ctx(new BigDecimal("12.00"), new BigDecimal("11.00"), new BigDecimal("0.50")));

    assertTrue(sigs.stream().anyMatch(s -> MonitorSignal.ATR_AMPLITUDE.equals(s.getSignalType())));
  }

  @Test
  void takeProfitTriggersWhenPriceAboveEntry() {
    pos.setEntryPrice(new BigDecimal("100.00"));
    pos.setTakeProfitPct(new BigDecimal("20.00"));

    List<MonitorSignal> sigs =
        engine.evaluate(
            ctx(new BigDecimal("125.00"), new BigDecimal("120.00"), new BigDecimal("2.00")));

    assertTrue(sigs.stream().anyMatch(s -> MonitorSignal.TAKE_PROFIT.equals(s.getSignalType())));
  }

  @Test
  void takeProfitDoesNotTriggerWhenPriceBelowTarget() {
    pos.setEntryPrice(new BigDecimal("100.00"));
    pos.setTakeProfitPct(new BigDecimal("20.00"));

    // target = 120; latest 115 < 120 → 不触发
    List<MonitorSignal> sigs =
        engine.evaluate(
            ctx(new BigDecimal("115.00"), new BigDecimal("110.00"), new BigDecimal("2.00")));

    assertTrue(sigs.stream().noneMatch(s -> MonitorSignal.TAKE_PROFIT.equals(s.getSignalType())));
  }

  @Test
  void stopLossPctTriggersWhenPriceBelowEntry() {
    pos.setEntryPrice(new BigDecimal("100.00"));
    pos.setStopLossPct(new BigDecimal("-8.00")); // -8% → floor 92.00

    List<MonitorSignal> sigs =
        engine.evaluate(
            ctx(new BigDecimal("90.00"), new BigDecimal("92.00"), new BigDecimal("2.00")));

    assertTrue(sigs.stream().anyMatch(s -> MonitorSignal.STOP_LOSS_PCT.equals(s.getSignalType())));
  }

  @Test
  void stopLossAtrTriggersWhenPriceBelowTrailStop() {
    pos.setEntryPrice(new BigDecimal("100.00"));
    pos.setUseAtr(1);
    pos.setPeakPrice(new BigDecimal("120.00"));
    pos.setAtrTrailMult(new BigDecimal("2.00"));
    pos.setAtrPeriod(14);

    // stopLine = 120 - 2 * 2 * 3 = 108; latest 110 > 108 → 不触发
    List<MonitorSignal> sigs1 =
        engine.evaluate(
            ctx(new BigDecimal("110.00"), new BigDecimal("108.00"), new BigDecimal("3.00")));
    assertTrue(
        sigs1.stream().noneMatch(s -> MonitorSignal.STOP_LOSS_ATR.equals(s.getSignalType())));

    // latest 105 < 108 → 触发
    List<MonitorSignal> sigs2 =
        engine.evaluate(
            ctx(new BigDecimal("105.00"), new BigDecimal("108.00"), new BigDecimal("3.00")));
    assertTrue(sigs2.stream().anyMatch(s -> MonitorSignal.STOP_LOSS_ATR.equals(s.getSignalType())));
  }

  @Test
  void nullCtxReturnsEmpty() {
    assertTrue(engine.evaluate(null).isEmpty());
  }

  @Test
  void missingLatestReturnsEmpty() {
    MonitorContext c =
        MonitorContext.builder().stockCode("000001.SZ").stockName("X").latest(null).build();
    assertTrue(engine.evaluate(c).isEmpty());
  }
}
