package com.quant.service;

import com.quant.entity.TradeStockFinancial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Ps10ValuationService TDD。
 * RED: 先写期望 → 跑不过 → 修代码
 */
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

    @Nested
    @DisplayName("适用性判断")
    class Applicability {

        @Test
        @DisplayName("净利率 ≥ 25% → 适用")
        void netMarginAbove25pct() {
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("15"),
                    new BigDecimal("10.0"),
                    "688401.SH",
                    List.of(makeFinancial(25.0, 10.0, 20.0))
            );
            assertThat(result.applicable()).isTrue();
        }

        @Test
        @DisplayName("净利率 < 25% → 不适用")
        void netMarginBelow25pct() {
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("15"),
                    new BigDecimal("10.0"),
                    "688401.SH",
                    List.of(makeFinancial(20.0, 10.0, 20.0))
            );
            assertThat(result.applicable()).isFalse();
            assertThat(result.verdict()).isEqualTo("—");
        }

        @Test
        @DisplayName("无财务数据 → 不适用")
        void noFinancialData() {
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("15"),
                    new BigDecimal("10.0"),
                    "688401.SH",
                    List.of()
            );
            assertThat(result.applicable()).isFalse();
        }
    }

    // ── 估值判定 ───────────────────────────────────────

    @Nested
    @DisplayName("10xPS 估值判定")
    class ValuationJudgement {

        /**
         * makeFinancial(25.0, 10.0, 20.0): netMargin=25%, revenue=10亿(单季), YoY=20%
         * TTM = 10×4(外推) = 40亿
         * growth = 20%
         * Y1 = 40×1.2 = 48亿, Y2 = 48×1.2 = 57.6亿
         * fairCapY1 = 48×10 = 480亿, fairCapY2 = 57.6×10 = 576亿
         */
        private List<TradeStockFinancial> baseFinancials() {
            return List.of(makeFinancial(25.0, 10.0, 20.0));
        }

        @Test
        @DisplayName("当前市值 100亿 < Y1×10=480亿 → 低估")
        void undervalued() {
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("100"), null, "688401.SH", baseFinancials());

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
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("500"), null, "688401.SH", baseFinancials());

            assertThat(result.verdict()).isEqualTo("合理");
        }

        @Test
        @DisplayName("当前市值 700亿 > Y2×10=576亿 → 泡沫")
        void bubble() {
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("700"), null, "688401.SH", baseFinancials());

            assertThat(result.verdict()).isEqualTo("泡沫");
        }

        @Test
        @DisplayName("市值为空 → verdict=—")
        void noMarketCap() {
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    null, null, "688401.SH", baseFinancials());

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
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("130"), null, "688401.SH", fin);

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
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("150"), null, "688401.SH", fin);

            assertThat(result.growthPct()).isEqualByComparingTo(new BigDecimal("50.00"));
            assertThat(result.revY1Yi()).isEqualByComparingTo(new BigDecimal("60.00")); // 40×1.5
        }

        @Test
        @DisplayName("YoY=5% → 提升到 15%")
        void lowYoYGuaranteed() {
            // YoY=5% < 15% → 提升到 15%
            List<TradeStockFinancial> fin = List.of(makeFinancial(25.0, 10.0, 5.0));
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("120"), null, "688401.SH", fin);

            assertThat(result.growthPct()).isEqualByComparingTo(new BigDecimal("15.00"));
            assertThat(result.revY1Yi()).isEqualByComparingTo(new BigDecimal("46.00")); // 40×1.15
        }

        @Test
        @DisplayName("YoY=NULL 或负值 → 保守估 20%")
        void negativeOrNullYoUsesDefault() {
            TradeStockFinancial fin = makeFinancial(25.0, 10.0, -5.0);
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("130"), null, "688401.SH", List.of(fin));

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
            List<TradeStockFinancial> fin = List.of(
                    makeFinancial(25.0, 3.0, 20.0),
                    makeFinancial(25.0, 2.5, 20.0),
                    makeFinancial(25.0, 2.5, 20.0),
                    makeFinancial(25.0, 2.0, 20.0)
            );
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("100"), null, "688401.SH", fin);

            assertThat(result.ttmRevenueYi()).isEqualByComparingTo(new BigDecimal("10.00")); // 3+2.5+2.5+2=10
            assertThat(result.revY1Yi()).isEqualByComparingTo(new BigDecimal("12.00")); // 10×1.2
        }

        @Test
        @DisplayName("不足4个季度 → 按均值外推")
        void lessThanFourQuartersExtrapolated() {
            // 只有3个正季度: 3+2.5+2.5=8，均值=8/3×4=10.67
            List<TradeStockFinancial> fin = List.of(
                    makeFinancial(25.0, 3.0, 20.0),
                    makeFinancial(25.0, 2.5, 20.0),
                    makeFinancial(25.0, 2.5, 20.0)
            );
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("100"), null, "688401.SH", fin);

            assertThat(result.ttmRevenueYi()).isEqualByComparingTo(new BigDecimal("10.67")); // 8/3*4
        }

        @Test
        @DisplayName("全部亏损季度 → verdict=—")
        void allNegativeQuarters() {
            List<TradeStockFinancial> fin = List.of(
                    makeFinancial(25.0, -3.0, 20.0),
                    makeFinancial(25.0, -2.5, 20.0),
                    makeFinancial(25.0, -2.5, 20.0),
                    makeFinancial(25.0, -2.0, 20.0)
            );
            Ps10ValuationService.Ps10Result result = service.evaluateFromMarketCap(
                    new BigDecimal("100"), null, "688401.SH", fin);

            assertThat(result.verdict()).isEqualTo("—");
            assertThat(result.commentary()).contains("全为负");
        }
    }

    // ── 辅助 ─────────────────────────────────────────

    // revenue 单位是元（不是亿），netMargin 是 %，revenueYoy 数据库存小数(如 0.20)或百分比(如 20)
    private TradeStockFinancial makeFinancial(double netMarginPct, double annualRevenueYi, double revYoyPct) {
        TradeStockFinancial f = new TradeStockFinancial();
        f.setNetMargin(BigDecimal.valueOf(netMarginPct));
        // 数据库存元 → 转为亿元：annualRevenueYi 亿 = annualRevenueYi × 1亿 元
        f.setRevenue(BigDecimal.valueOf(annualRevenueYi * 1_0000_0000));
        // 数据库存小数(如 0.20=20%) 或百分比(如 20=20%)
        // PracticalSelectService 第 735 行用 yoy/100 判断，说明存的是小数形式
        f.setRevenueYoy(BigDecimal.valueOf(revYoyPct / 100.0));
        f.setReportDate(LocalDate.of(2026, 3, 31));
        return f;
    }
}
