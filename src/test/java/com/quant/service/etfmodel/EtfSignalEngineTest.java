package com.quant.service.etfmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.quant.entity.EtfPool;

class EtfSignalEngineTest {

  private EtfSignalEngine engine;

  @BeforeEach
  void setUp() {
    engine = new EtfSignalEngine();
  }

  private EtfPositionView.EtfPositionViewBuilder pos(String category) {
    return EtfPositionView.builder()
        .poolId(1L)
        .stockCode("513100.SH")
        .stockName("纳指ETF")
        .category(category)
        .shares(10000)
        .netInvested(new BigDecimal("10000.00"))
        .dilutedCost(new BigDecimal("1.000"))
        .recoupStatus(EtfPool.RECOUP_NONE);
  }

  /* ─────────── 止盈三段 ─────────── */

  @Test
  void tp1TriggersAtPlus5Pct() {
    List<EtfSignal> sigs =
        engine.evaluateThresholds(pos(EtfPool.CATEGORY_BROAD).build(), new BigDecimal("1.050"));
    assertThat(sigs).extracting(EtfSignal::getSignalType).containsExactly(EtfSignal.TP1);
    assertThat(sigs.get(0).getTitle()).contains("减 1/3");
  }

  @Test
  void tp1NotTriggeredBelow5Pct() {
    List<EtfSignal> sigs =
        engine.evaluateThresholds(pos(EtfPool.CATEGORY_BROAD).build(), new BigDecimal("1.049"));
    assertThat(sigs).isEmpty();
  }

  @Test
  void tp1DoneSuppressesRepeatUntilTp2() {
    EtfPositionView p = pos(EtfPool.CATEGORY_BROAD).tp1Done(true).build();
    // +7%：TP1 已执行、未到 +10% → 无信号
    assertThat(engine.evaluateThresholds(p, new BigDecimal("1.070"))).isEmpty();
  }

  @Test
  void tp2TriggersAtPlus10PctAfterTp1() {
    EtfPositionView p = pos(EtfPool.CATEGORY_BROAD).tp1Done(true).build();
    List<EtfSignal> sigs = engine.evaluateThresholds(p, new BigDecimal("1.100"));
    assertThat(sigs).extracting(EtfSignal::getSignalType).containsExactly(EtfSignal.TP2);
    assertThat(sigs.get(0).getContent()).contains("移动止盈");
  }

  @Test
  void tp2RequiresTp1First() {
    // 直接跳到 +12%：先提醒 TP1 档
    List<EtfSignal> sigs =
        engine.evaluateThresholds(pos(EtfPool.CATEGORY_BROAD).build(), new BigDecimal("1.120"));
    assertThat(sigs).extracting(EtfSignal::getSignalType).containsExactly(EtfSignal.TP1);
  }

  /* ─────────── 止损分档：宽基 ─────────── */

  @Test
  void broadSl1TriggersAtMinus15() {
    List<EtfSignal> sigs =
        engine.evaluateThresholds(pos(EtfPool.CATEGORY_BROAD).build(), new BigDecimal("0.850"));
    assertThat(sigs).extracting(EtfSignal::getSignalType).containsExactly(EtfSignal.SL1);
  }

  @Test
  void broadSl1NotTriggeredAtMinus10() {
    // 宽基第一档是 -15%，-10% 不触发
    List<EtfSignal> sigs =
        engine.evaluateThresholds(pos(EtfPool.CATEGORY_BROAD).build(), new BigDecimal("0.900"));
    assertThat(sigs).isEmpty();
  }

  @Test
  void broadSl2TriggersAtMinus30AfterSl1() {
    EtfPositionView p = pos(EtfPool.CATEGORY_BROAD).sl1Done(true).build();
    List<EtfSignal> sigs = engine.evaluateThresholds(p, new BigDecimal("0.700"));
    assertThat(sigs).extracting(EtfSignal::getSignalType).containsExactly(EtfSignal.SL2);
    assertThat(sigs.get(0).getContent()).contains("留 1/4 长持");
  }

  /* ─────────── 止损分档：行业/主题 ─────────── */

  @Test
  void sectorSl1TriggersAtMinus10() {
    List<EtfSignal> sigs =
        engine.evaluateThresholds(pos(EtfPool.CATEGORY_SECTOR).build(), new BigDecimal("0.900"));
    assertThat(sigs).extracting(EtfSignal::getSignalType).containsExactly(EtfSignal.SL1);
  }

  @Test
  void sectorSl2ClearAllAtMinus18EvenWithoutSl1() {
    // -18% 无条件清仓，不要求先减半
    List<EtfSignal> sigs =
        engine.evaluateThresholds(pos(EtfPool.CATEGORY_SECTOR).build(), new BigDecimal("0.820"));
    assertThat(sigs).extracting(EtfSignal::getSignalType).containsExactly(EtfSignal.SL2);
    assertThat(sigs.get(0).getTitle()).contains("无条件清仓");
  }

  @Test
  void doneFlagsSuppressStopLossRepeat() {
    EtfPositionView p = pos(EtfPool.CATEGORY_SECTOR).sl1Done(true).sl2Done(true).build();
    assertThat(engine.evaluateThresholds(p, new BigDecimal("0.700"))).isEmpty();
  }

  @Test
  void noSignalWhenNoShares() {
    EtfPositionView p = pos(EtfPool.CATEGORY_BROAD).shares(0).dilutedCost(null).build();
    assertThat(engine.evaluateThresholds(p, new BigDecimal("1.200"))).isEmpty();
  }

  /* ─────────── 移动止盈（收盘判定） ─────────── */

  @Test
  void trailExitTriggersWhenCloseBelowMa20AfterTp2() {
    EtfPositionView p = pos(EtfPool.CATEGORY_BROAD).tp1Done(true).tp2Done(true).build();
    EtfSignal sig =
        engine.evaluateTrailExit(p, new BigDecimal("1.050"), new BigDecimal("1.060"));
    assertThat(sig).isNotNull();
    assertThat(sig.getSignalType()).isEqualTo(EtfSignal.TRAIL_EXIT);
  }

  @Test
  void trailExitNotTriggeredAboveMa20() {
    EtfPositionView p = pos(EtfPool.CATEGORY_BROAD).tp1Done(true).tp2Done(true).build();
    assertThat(engine.evaluateTrailExit(p, new BigDecimal("1.070"), new BigDecimal("1.060")))
        .isNull();
  }

  @Test
  void trailExitRequiresTp2Done() {
    EtfPositionView p = pos(EtfPool.CATEGORY_BROAD).build();
    assertThat(engine.evaluateTrailExit(p, new BigDecimal("1.000"), new BigDecimal("1.060")))
        .isNull();
  }

  /* ─────────── 组合级保命线 ─────────── */

  @Test
  void portfolioGuardTriggersAt20PctDrawdown() {
    EtfSignal sig =
        engine.evaluatePortfolioGuard(
            new BigDecimal("80000.00"),
            new BigDecimal("100000.00"),
            BigDecimal.valueOf(20),
            7);
    assertThat(sig).isNotNull();
    assertThat(sig.getSignalType()).isEqualTo(EtfSignal.PORTFOLIO_GUARD);
    assertThat(sig.getTitle()).contains("整体降 1/4");
  }

  @Test
  void portfolioGuardNotTriggeredBelowThreshold() {
    assertThat(
            engine.evaluatePortfolioGuard(
                new BigDecimal("85000.00"),
                new BigDecimal("100000.00"),
                BigDecimal.valueOf(20),
                7))
        .isNull();
  }

  @Test
  void drawdownPctComputation() {
    assertThat(EtfSignalEngine.drawdownPct(new BigDecimal("90000"), new BigDecimal("100000")))
        .isEqualByComparingTo("10.00");
    assertThat(EtfSignalEngine.drawdownPct(new BigDecimal("100000"), null)).isNull();
  }

  /* ─────────── 回补 ─────────── */

  @Test
  void weeklyCloseAboveMa5Check() {
    assertThat(engine.weeklyCloseAboveMa5(new BigDecimal("1.05"), new BigDecimal("1.00"))).isTrue();
    assertThat(engine.weeklyCloseAboveMa5(new BigDecimal("0.99"), new BigDecimal("1.00"))).isFalse();
    assertThat(engine.weeklyCloseAboveMa5(null, new BigDecimal("1.00"))).isFalse();
  }

  @Test
  void recoupReadyIsBuyAdvice() {
    EtfSignal sig =
        engine.recoupReady(
            pos(EtfPool.CATEGORY_BROAD).build(), new BigDecimal("1.05"), new BigDecimal("1.00"));
    assertThat(sig.isBuyAdvice()).isTrue();
    assertThat(sig.getSignalType()).isEqualTo(EtfSignal.RECOUP_READY);
  }

  /* ─────────── 趋势判档 ─────────── */

  @Test
  void trendDownSuggestsLightTier() {
    EtfSignalEngine.TrendAdvice advice =
        engine.trendAdvice(
            new BigDecimal("0.95"),
            new BigDecimal("0.96"),
            new BigDecimal("1.00"),
            new BigDecimal("-0.01"),
            new BigDecimal("-5.00"),
            BigDecimal.valueOf(15));
    assertThat(advice.trend()).isEqualTo("DOWN");
    assertThat(advice.tier()).isEqualTo(EtfSignalEngine.BuyTier.LIGHT);
  }

  @Test
  void bigRiseSuggestsLightTierEvenInUptrend() {
    EtfSignalEngine.TrendAdvice advice =
        engine.trendAdvice(
            new BigDecimal("1.20"),
            new BigDecimal("1.15"),
            new BigDecimal("1.10"),
            new BigDecimal("0.02"),
            new BigDecimal("18.00"),
            BigDecimal.valueOf(15));
    assertThat(advice.trend()).isEqualTo("UP");
    assertThat(advice.tier()).isEqualTo(EtfSignalEngine.BuyTier.LIGHT);
  }

  @Test
  void uptrendModestRiseSuggestsMidTier() {
    EtfSignalEngine.TrendAdvice advice =
        engine.trendAdvice(
            new BigDecimal("1.10"),
            new BigDecimal("1.08"),
            new BigDecimal("1.05"),
            new BigDecimal("0.02"),
            new BigDecimal("6.00"),
            BigDecimal.valueOf(15));
    assertThat(advice.tier()).isEqualTo(EtfSignalEngine.BuyTier.MID);
  }

  @Test
  void missingMaDataYieldsUnknown() {
    EtfSignalEngine.TrendAdvice advice =
        engine.trendAdvice(new BigDecimal("1.00"), null, null, null, null, BigDecimal.valueOf(15));
    assertThat(advice.trend()).isEqualTo("UNKNOWN");
    assertThat(advice.tier()).isNull();
  }
}
