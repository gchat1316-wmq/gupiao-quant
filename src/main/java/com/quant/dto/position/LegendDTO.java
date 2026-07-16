package com.quant.dto.position;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Data;

/** 传奇交易员案例数据 - 致敬方法论来源。 数据来源:用户提供的脚本化讲稿,均为公开可查的华尔街传奇。 */
@Data
@Builder
public class LegendDTO {

  private String name;
  private String nickname;
  private String achievement;
  private String period;
  private String startingCapital;
  private String finalCapital;
  private String coreQuote;
  private String principle;

  /** Minervini 风格 1:3 风报档位预设 */
  public static java.util.List<RiskRewardPreset> presets() {
    return java.util.Arrays.asList(
        RiskRewardPreset.builder()
            .label("1:1 保守")
            .ratioValue(BigDecimal.valueOf(1))
            .breakEvenWinRate(new java.math.BigDecimal("0.5000"))
            .note("盈亏平衡需 50% 胜率,业余线")
            .build(),
        RiskRewardPreset.builder()
            .label("1:2 标准")
            .ratioValue(BigDecimal.valueOf(2))
            .breakEvenWinRate(new java.math.BigDecimal("0.3333"))
            .note("盈亏平衡需 ~33% 胜率,具备统计优势")
            .build(),
        RiskRewardPreset.builder()
            .label("1:3 Minervini")
            .ratioValue(BigDecimal.valueOf(3))
            .breakEvenWinRate(new java.math.BigDecimal("0.2500"))
            .note("Mark Minervini 的最低进场门槛")
            .build(),
        RiskRewardPreset.builder()
            .label("1:5 进取")
            .ratioValue(BigDecimal.valueOf(5))
            .breakEvenWinRate(new java.math.BigDecimal("0.1667"))
            .note("盈亏平衡只需 ~17%,但回撤放大")
            .build(),
        RiskRewardPreset.builder()
            .label("1:10 大波段")
            .ratioValue(BigDecimal.valueOf(10))
            .breakEvenWinRate(new java.math.BigDecimal("0.0909"))
            .note("Livermore 风格的「让利润奔跑」档")
            .build());
  }

  @Data
  @Builder
  public static class RiskRewardPreset {
    private String label;
    private BigDecimal ratioValue;
    private BigDecimal breakEvenWinRate;
    private String note;
  }

  public static java.util.List<LegendDTO> all() {
    return java.util.Arrays.asList(
        LegendDTO.builder()
            .name("Dan Zanger")
            .nickname("形态交易之王")
            .achievement("1 万 → 4200 万美元")
            .period("23 个月 (1987-1989)")
            .startingCapital("$10,000")
            .finalCapital("$42,000,000")
            .coreQuote("\"我只做我看到的,不预测\"")
            .principle("图形识别 + 严格风报比,不预测宏观,只截获大波段。")
            .build(),
        LegendDTO.builder()
            .name("Christian Kolmagie")
            .nickname("波段持仓派")
            .achievement("5000 → 1 亿+美元")
            .period("多年复利")
            .startingCapital("$5,000")
            .finalCapital("$100,000,000+")
            .coreQuote("\"我持仓数月,只为最大化上涨空间\"")
            .principle("只要风报比在 1:3 以上,愿意持仓数月让利润奔跑。")
            .build(),
        LegendDTO.builder()
            .name("Richard Dennis")
            .nickname("海龟交易之父")
            .achievement("1600 → 2 亿美元")
            .period("1970s-1990s")
            .startingCapital("$1,600")
            .finalCapital("$200,000,000")
            .coreQuote("\"交易是可以被教会的能力\"")
            .principle("用正期望值的系统训练海龟 — 纪律胜于直觉,海龟团队累计盈利 1.5 亿美元。")
            .build(),
        LegendDTO.builder()
            .name("Jesse Livermore")
            .nickname("少年作手")
            .achievement("巅峰身价 1 亿美元(≈ 今日 20 亿)")
            .period("1900s-1940s")
            .startingCapital("$5 起家")
            .finalCapital("~$100,000,000")
            .coreQuote("\"真正的大钱来自大的波段\"")
            .principle("不必每次都正确,只要正确时赚得够大,就能压倒所有亏损。")
            .build(),
        LegendDTO.builder()
            .name("Michael Marcus")
            .nickname("商品交易大师")
            .achievement("3 万 → 8000 万美元")
            .period("1970s-2000s")
            .startingCapital("$30,000")
            .finalCapital("$80,000,000")
            .coreQuote("\"让我害怕的并不是亏钱,而是失去控制\"")
            .principle("仓位管理是控制的核心:再有把握也不放大单笔风险,严格保持仓位一致性。")
            .build(),
        LegendDTO.builder()
            .name("Mark Minervini")
            .nickname("SEPA 创始人")
            .achievement("3 万 → 7200 万美元(复利)")
            .period("1980s-至今")
            .startingCapital("$30,000")
            .finalCapital("$72,000,000")
            .coreQuote("\"除非风险回报比至少 1:3,否则我不会进场\"")
            .principle("Minervini 原则:风险回报比不足 1:3 一律不进场;高 R:R 让低胜率也能盈利。")
            .build());
  }
}
