package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.entity.TradeStockFinancial;
import com.quant.service.aistockdata.AStockDataQuoteService;

/** Ps10ValuationService TDD。 RED: 先写期望 → 跑不过 → 修代码 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Ps10ValuationService")
class Ps10ValuationServiceTest {

  @Mock private AStockDataQuoteService aStockDataQuoteService;

  private Ps10ValuationService service;

  @BeforeEach
  void setUp() {
    service = new Ps10ValuationService(aStockDataQuoteService);
  }

  // ── 适用性判断 ─────────────────────────────────────
  // 2026-07-01 改：净利率"接近"即可，不强制 ≥ 25%。
  // 截图里的 6.29%（长盈通）/14.51%（东威科技）都已纳入 PS 估值。
  // 适用性只受"是否有财务数据"约束。

  @Nested
  @DisplayName("适用性判断（净利率接近即可，无硬门槛）")
  class Applicability {

    @Test
    @DisplayName("净利率 ≥ 25% → 适用")
    void netMarginAbove25pct() {
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(
              new BigDecimal("15"),
              new BigDecimal("10.0"),
              "688401.SH",
              List.of(makeFinancial(25.0, 10.0, 20.0)));
      assertThat(result.applicable()).isTrue();
    }

    @Test
    @DisplayName("净利率 14.5%（东威科技）→ 仍适用，verdict=低估（mc<Y1×10）")
    void netMarginAround15pct() {
      // 营收 10 亿/单季 × 4 = TTM 40 亿；YoY 20% → Y1=48, Y2=57.6, fairCapY2=576
      // mc=250 < Y1×10=480 → 低估
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(
              new BigDecimal("250"),
              new BigDecimal("10.0"),
              "688700.SH",
              List.of(makeFinancial(14.51, 10.0, 20.0)));
      assertThat(result.applicable()).isTrue();
      assertThat(result.verdict()).isEqualTo("低估");
      assertThat(result.netMarginPct()).isEqualByComparingTo(new BigDecimal("14.51"));
    }

    @Test
    @DisplayName("净利率 6.29%（长盈通）→ 仍适用，远低于 25% 也照样套公式，verdict=泡沫")
    void netMarginWayBelow25pct() {
      // 营收 5 亿/单季 × 4 = TTM 20 亿；YoY 30% → Y1=26, Y2=33.8, fairCapY2=338
      // mc=400 > Y2×10=338 → 泡沫
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(
              new BigDecimal("400"),
              new BigDecimal("10.0"),
              "688143.SH",
              List.of(makeFinancial(6.29, 5.0, 30.0)));
      assertThat(result.applicable()).isTrue();
      assertThat(result.verdict()).isEqualTo("泡沫");
      assertThat(result.netMarginPct()).isEqualByComparingTo(new BigDecimal("6.29"));
    }

    @Test
    @DisplayName("净利率为 NULL → commentary 提示，但 verdict 仍可给出")
    void netMarginNull() {
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(
              new BigDecimal("100"),
              new BigDecimal("10.0"),
              "688401.SH",
              List.of(makeFinancial(null, 10.0, 20.0)));
      assertThat(result.applicable()).isTrue();
      // commentary 应明确说明缺净利率数据
      assertThat(result.commentary()).contains("净利率");
    }

    @Test
    @DisplayName("无财务数据 → 不适用")
    void noFinancialData() {
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(
              new BigDecimal("15"), new BigDecimal("10.0"), "688401.SH", List.of());
      assertThat(result.applicable()).isFalse();
    }
  }

  // ── 估值判定 ───────────────────────────────────────

  @Nested
  @DisplayName("10xPS 估值判定")
  class ValuationJudgement {

    /**
     * makeFinancial(25.0, 10.0, 20.0): netMargin=25%, revenue=10亿(单季), YoY=20% TTM = 10×4(外推) = 40亿
     * growth = 20% Y1 = 40×1.2 = 48亿, Y2 = 48×1.2 = 57.6亿 fairCapY1 = 48×10 = 480亿, fairCapY2 =
     * 57.6×10 = 576亿
     */
    private List<TradeStockFinancial> baseFinancials() {
      return List.of(makeFinancial(25.0, 10.0, 20.0));
    }

    @Test
    @DisplayName("当前市值 100亿 < Y1×10=480亿 → 低估")
    void undervalued() {
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(new BigDecimal("100"), null, "688401.SH", baseFinancials());

      assertThat(result.verdict()).isEqualTo("低估");
      assertThat(result.ttmRevenueYi()).isEqualByComparingTo(new BigDecimal("40.00"));
      assertThat(result.revY1Yi()).isEqualByComparingTo(new BigDecimal("48.00"));
      assertThat(result.revY2Yi()).isEqualByComparingTo(new BigDecimal("57.60"));
      assertThat(result.fairCapY1Yi()).isEqualByComparingTo(new BigDecimal("480.00"));
      assertThat(result.fairCapY2Yi()).isEqualByComparingTo(new BigDecimal("576.00"));
    }

    @Test
    @DisplayName("当前市值 500亿，Y1×10=480亿 < 500亿 < Y2×10=576亿 → 合理")
    void fair() {
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(new BigDecimal("500"), null, "688401.SH", baseFinancials());

      assertThat(result.verdict()).isEqualTo("合理");
    }

    @Test
    @DisplayName("当前市值 700亿 > Y2×10=576亿 → 泡沫")
    void bubble() {
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(new BigDecimal("700"), null, "688401.SH", baseFinancials());

      assertThat(result.verdict()).isEqualTo("泡沫");
    }

    @Test
    @DisplayName("市值为空 → verdict=—")
    void noMarketCap() {
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(null, null, "688401.SH", baseFinancials());

      assertThat(result.verdict()).isEqualTo("—");
    }
  }

  // ── 增速处理 ───────────────────────────────────────

  @Nested
  @DisplayName("增速处理")
  class GrowthRate {

    @Test
    @DisplayName("YoY=30% → 限制到 50%")
    void highYoYCapped() {
      // makeFinancial(25.0, 10.0, 30.0) → revenue=10亿(单季), YoY=30%
      // TTM=40亿, Y1=40×1.3=52亿, Y1×10=520亿
      List<TradeStockFinancial> fin = List.of(makeFinancial(25.0, 10.0, 30.0));
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(new BigDecimal("130"), null, "688401.SH", fin);

      assertThat(result.growthPct()).isEqualByComparingTo(new BigDecimal("30.00"));
      assertThat(result.ttmRevenueYi()).isEqualByComparingTo(new BigDecimal("40.00")); // 10×4
      assertThat(result.revY1Yi()).isEqualByComparingTo(new BigDecimal("52.00")); // 40×1.3
      assertThat(result.fairCapY1Yi()).isEqualByComparingTo(new BigDecimal("520.00")); // 52×10
    }

    @Test
    @DisplayName("YoY=100% → 限制到 50%（过高不合理）")
    void excessiveYoYCapped() {
      // YoY=100% → capped at 50%
      List<TradeStockFinancial> fin = List.of(makeFinancial(25.0, 10.0, 100.0));
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(new BigDecimal("150"), null, "688401.SH", fin);

      assertThat(result.growthPct()).isEqualByComparingTo(new BigDecimal("50.00"));
      assertThat(result.revY1Yi()).isEqualByComparingTo(new BigDecimal("60.00")); // 40×1.5
    }

    @Test
    @DisplayName("YoY=5% → 提升到 15%")
    void lowYoYGuaranteed() {
      // YoY=5% < 15% → 提升到 15%
      List<TradeStockFinancial> fin = List.of(makeFinancial(25.0, 10.0, 5.0));
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(new BigDecimal("120"), null, "688401.SH", fin);

      assertThat(result.growthPct()).isEqualByComparingTo(new BigDecimal("15.00"));
      assertThat(result.revY1Yi()).isEqualByComparingTo(new BigDecimal("46.00")); // 40×1.15
    }

    @Test
    @DisplayName("YoY=NULL 或负值 → 保守估 20%")
    void negativeOrNullYoUsesDefault() {
      TradeStockFinancial fin = makeFinancial(25.0, 10.0, -5.0);
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(new BigDecimal("130"), null, "688401.SH", List.of(fin));

      assertThat(result.growthPct()).isEqualByComparingTo(new BigDecimal("20.00"));
      assertThat(result.revY1Yi()).isEqualByComparingTo(new BigDecimal("48.00")); // 40×1.2
    }
  }

  // ── TTM 计算 ───────────────────────────────────────

  @Nested
  @DisplayName("TTM 营收计算")
  class TtmRevenue {

    @Test
    @DisplayName("4个正季度 → 直接求和")
    void fourPositiveQuarters() {
      List<TradeStockFinancial> fin =
          List.of(
              makeFinancial(25.0, 3.0, 20.0),
              makeFinancial(25.0, 2.5, 20.0),
              makeFinancial(25.0, 2.5, 20.0),
              makeFinancial(25.0, 2.0, 20.0));
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(new BigDecimal("100"), null, "688401.SH", fin);

      assertThat(result.ttmRevenueYi())
          .isEqualByComparingTo(new BigDecimal("10.00")); // 3+2.5+2.5+2=10
      assertThat(result.revY1Yi()).isEqualByComparingTo(new BigDecimal("12.00")); // 10×1.2
    }

    @Test
    @DisplayName("不足4个季度 → 按均值外推")
    void lessThanFourQuartersExtrapolated() {
      // 只有3个正季度: 3+2.5+2.5=8，均值=8/3×4=10.67
      List<TradeStockFinancial> fin =
          List.of(
              makeFinancial(25.0, 3.0, 20.0),
              makeFinancial(25.0, 2.5, 20.0),
              makeFinancial(25.0, 2.5, 20.0));
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(new BigDecimal("100"), null, "688401.SH", fin);

      assertThat(result.ttmRevenueYi()).isEqualByComparingTo(new BigDecimal("10.67")); // 8/3*4
    }

    @Test
    @DisplayName("全部亏损季度 → verdict=—")
    void allNegativeQuarters() {
      List<TradeStockFinancial> fin =
          List.of(
              makeFinancial(25.0, -3.0, 20.0),
              makeFinancial(25.0, -2.5, 20.0),
              makeFinancial(25.0, -2.5, 20.0),
              makeFinancial(25.0, -2.0, 20.0));
      Ps10ValuationService.Ps10Result result =
          service.evaluateFromMarketCap(new BigDecimal("100"), null, "688401.SH", fin);

      assertThat(result.verdict()).isEqualTo("—");
      assertThat(result.commentary()).contains("全为负");
    }
  }

  // ── 辅助 ─────────────────────────────────────────

  // revenue 单位是元（不是亿），netMargin 是 %，revenueYoy 数据库存小数(如 0.20)或百分比(如 20)
  private TradeStockFinancial makeFinancial(
      double netMarginPct, double annualRevenueYi, double revYoyPct) {
    return makeFinancial((Double) netMarginPct, annualRevenueYi, revYoyPct);
  }

  /** 允许 netMargin 为 null 的重载，覆盖"缺净利率数据"场景 */
  private TradeStockFinancial makeFinancial(
      Double netMarginPct, double annualRevenueYi, double revYoyPct) {
    TradeStockFinancial f = new TradeStockFinancial();
    f.setNetMargin(netMarginPct == null ? null : BigDecimal.valueOf(netMarginPct));
    // 数据库存元 → 转为亿元：annualRevenueYi 亿 = annualRevenueYi × 1亿 元
    f.setRevenue(BigDecimal.valueOf(annualRevenueYi * 1_0000_0000));
    // 数据库存小数(如 0.20=20%) 或百分比(如 20=20%)
    f.setRevenueYoy(BigDecimal.valueOf(revYoyPct / 100.0));
    f.setReportDate(LocalDate.of(2026, 3, 31));
    return f;
  }
}
