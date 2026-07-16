package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.quant.service.invest.InvestValuationService;
import com.quant.service.invest.InvestValuationService.ValuationVerdict;

/**
 * 覆盖 10×PS 估值三档 + 程度字段： - 低估 → 用 Y1 (2027) 为参照，degree 为负 - 合理 → 不输出 degree，强制要求前后端都给 null - 泡沫 → 用
 * Y2 (2028) 为参照，degree 为正 - 缺数据 → 全 null
 *
 * <p>重新设计的目的：单一函数同时返回 level/degree/refYear，让前端不再自己 在另一个 inferValuationRange() 副本里再算一遍。
 */
@DisplayName("InvestService.ValuationVerdict - 10×PS 估值三元组")
class InvestServiceValuationRangeTest {

  /** 市值 100 亿，Y1=20 亿 → 10×PS = 200 亿 → 市值 100 远低于 200（-50%）→ 低估 */
  @Test
  @DisplayName("低估：市值 < Y1×10 → level=低估, refYear=2027, degree<0")
  void undervaluedUsesY1() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("100"), new BigDecimal("20"), null);

    assertThat(v.level()).isEqualTo("低估");
    assertThat(v.refYear()).isEqualTo(2027);
    assertThat(v.degree()).isNotNull();
    // 100 / 200 - 1 = -0.5 → -50.00%
    assertThat(v.degree()).isCloseTo(new BigDecimal("-50.00"), within(new BigDecimal("0.01")));
  }

  /** 市值 1500 亿，Y2=100 亿 → 10×PS = 1000 亿 → 超 50% → 泡沫 */
  @Test
  @DisplayName("泡沫：市值 > Y2×10 → level=泡沫, refYear=2028, degree>0")
  void bubbleUsesY2() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("1500"),
            new BigDecimal("80"), // Y1 fair = 800，1500 已超（但要靠 Y2 catch）
            new BigDecimal("100")); // Y2 fair = 1000，1500/1000 = +50%

    assertThat(v.level()).isEqualTo("泡沫");
    assertThat(v.refYear()).isEqualTo(2028);
    assertThat(v.degree()).isCloseTo(new BigDecimal("50.00"), within(new BigDecimal("0.01")));
  }

  /** 市值正好夹在 Y1×10 和 Y2×10 之间 → 合理，2026-07-01 改：合理时也输出偏离 */
  @Test
  @DisplayName("合理：Y1×10 ≤ 市值 ≤ Y2×10 → level=合理, degree=距 Y1/Y2 更近的那年")
  void fairZoneNoDegree() {
    // mc=250, Y1=20 (fair=200), Y2=30 (fair=300)
    // 距 Y1=+25%, 距 Y2=-16.7% → 选 Y2
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("250"), new BigDecimal("20"), new BigDecimal("30"));

    assertThat(v.level()).isEqualTo("合理");
    assertThat(v.refYear()).isEqualTo(2028);
    assertThat(v.degree()).isCloseTo(new BigDecimal("-16.67"), within(new BigDecimal("0.01")));
  }

  /** 边界：市值正好 = Y1×10，不算低估 → 合理，2026-07-01 改：degree=0 不是 null */
  @Test
  @DisplayName("边界：市值 = Y1×10 → level=合理, degree=0, refYear=2027")
  void boundaryEqualY1FairIsFair() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("200"), new BigDecimal("20"), null);

    assertThat(v.level()).isEqualTo("合理");
    assertThat(v.refYear()).isEqualTo(2027);
    assertThat(v.degree()).isCloseTo(new BigDecimal("0.00"), within(new BigDecimal("0.01")));
  }

  /** 边界：市值正好 = Y2×10，不算泡沫 → 合理，2026-07-01 改：degree=0 不是 null */
  @Test
  @DisplayName("边界：市值 = Y2×10 → level=合理, degree=0, refYear=2028")
  void boundaryEqualY2FairIsFair() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("300"), null, new BigDecimal("30"));

    assertThat(v.level()).isEqualTo("合理");
    assertThat(v.refYear()).isEqualTo(2028);
    assertThat(v.degree()).isCloseTo(new BigDecimal("0.00"), within(new BigDecimal("0.01")));
  }

  // ── 合理时也要输出偏离百分比（refYear = 距 Y1/Y2 更近的那年）───

  /** 思泰克：mc=113.7, Y1×10=90.8, Y2×10=118 → 距 Y1=25.2%, 距 Y2=-3.6% → 选 Y2 */
  @Test
  @DisplayName("合理时（思泰克）→ 距 Y2 更近，refYear=2028, degree=-3.64%（距泡沫还差3.64%）")
  void fairZoneCloserToY2() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("113.7"), new BigDecimal("9.08"), new BigDecimal("11.80"));

    assertThat(v.level()).isEqualTo("合理");
    assertThat(v.refYear()).isEqualTo(2028);
    assertThat(v.degree()).isCloseTo(new BigDecimal("-3.64"), within(new BigDecimal("0.01")));
  }

  /** mc=95, Y1=9.08, Y2=11.8 → 距 Y1=4.6%, 距 Y2=-19.5% → 选 Y1 */
  @Test
  @DisplayName("合理时（接近 Y1）→ 距 Y1 更近，refYear=2027, degree=+4.6%")
  void fairZoneCloserToY1() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("95"), new BigDecimal("9.08"), new BigDecimal("11.80"));

    assertThat(v.level()).isEqualTo("合理");
    assertThat(v.refYear()).isEqualTo(2027);
    assertThat(v.degree()).isCloseTo(new BigDecimal("4.63"), within(new BigDecimal("0.01")));
  }

  /** 中点：mc 在 (Y1×10, Y2×10) 的算术中点 → 距 Y1 大、距 Y2 小 → 选 Y2 */
  @Test
  @DisplayName("合理时（中点）→ 距 Y2 更近")
  void fairZoneMidpoint() {
    // Y1×10=200, Y2×10=300 → 算术中点 mc=250
    // 距 Y1=(250-200)/200=25%, 距 Y2=(250-300)/300=-16.7% → 选 Y2
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("250"), new BigDecimal("20"), new BigDecimal("30"));

    assertThat(v.level()).isEqualTo("合理");
    assertThat(v.refYear()).isEqualTo(2028);
    assertThat(v.degree()).isCloseTo(new BigDecimal("-16.67"), within(new BigDecimal("0.01")));
  }

  /** 缺 Y1：合理时只能用 Y2 算 degree */
  @Test
  @DisplayName("合理时（缺 Y1）→ 只能用 Y2 算，refYear=2028")
  void fairZoneWithoutY1() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("120"), null, new BigDecimal("20"));

    assertThat(v.level()).isEqualTo("合理");
    assertThat(v.refYear()).isEqualTo(2028);
    // 120 / (20*10) - 1 = -40%
    assertThat(v.degree()).isCloseTo(new BigDecimal("-40.00"), within(new BigDecimal("0.01")));
  }

  /** 缺 Y2：合理时只能用 Y1 算 degree */
  @Test
  @DisplayName("合理时（缺 Y2）→ 只能用 Y1 算，refYear=2027")
  void fairZoneWithoutY2() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("250"), new BigDecimal("20"), null);

    assertThat(v.level()).isEqualTo("合理");
    assertThat(v.refYear()).isEqualTo(2027);
    // 250 / (20*10) - 1 = 25%
    assertThat(v.degree()).isCloseTo(new BigDecimal("25.00"), within(new BigDecimal("0.01")));
  }

  /** 没市值 → 全 null，前端用 — 占位 */
  @Test
  @DisplayName("缺市值 → 全 null")
  void noMarketCap() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(null, new BigDecimal("20"), null);
    assertThat(v.level()).isNull();
    assertThat(v.degree()).isNull();
    assertThat(v.refYear()).isNull();
  }

  /** Y1/Y2 都缺 → 无参照系 → 全 null */
  @Test
  @DisplayName("缺所有预测 → 全 null")
  void noForecasts() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(new BigDecimal("500"), null, null);
    assertThat(v.level()).isNull();
    assertThat(v.degree()).isNull();
    assertThat(v.refYear()).isNull();
  }

  /** 仅 Y2：市值 > Y2×10 仍然判泡沫（即便没有 Y1） */
  @Test
  @DisplayName("仅 Y2 + 市值远超 → 仍判泡沫，refYear=2028")
  void bubbleWithOnlyY2() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("1500"), null, new BigDecimal("100"));

    assertThat(v.level()).isEqualTo("泡沫");
    assertThat(v.refYear()).isEqualTo(2028);
    assertThat(v.degree()).isCloseTo(new BigDecimal("50.00"), within(new BigDecimal("0.01")));
  }

  /** 仅 Y1：没有 Y2 所以永远不会判泡沫，市值即便远超也只能判合理 */
  @Test
  @DisplayName("仅 Y1 + 市值远超 → 仍判合理，degree=基于 Y1 算（很大正数）")
  void fairWithOnlyY1Above() {
    ValuationVerdict v =
        InvestValuationService.inferValuationRange(
            new BigDecimal("9999"), new BigDecimal("20"), null);

    assertThat(v.level()).isEqualTo("合理");
    // 9999 / (20*10) - 1 = 4899.5%
    assertThat(v.refYear()).isEqualTo(2027);
    assertThat(v.degree()).isCloseTo(new BigDecimal("4899.50"), within(new BigDecimal("0.01")));
  }
}
