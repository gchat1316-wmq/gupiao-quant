package com.quant.service.position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.quant.dto.position.LegendDTO;
import com.quant.dto.position.PositionAdviceDTO;
import com.quant.dto.position.PositionAdviceRequest;

/**
 * 仓位管理系统 — 三大数学公式实现:
 *
 * <p>1) 风险回报比 R:R = (entry - stopLoss) : (target - entry) 盈亏平衡胜率 = 1 / (1 + reward/risk)
 *
 * <p>2) 期望值 EV = winRate * avgWin - (1 - winRate) * avgLoss 其中 avgWin = target - entry, avgLoss =
 * entry - stopLoss,均按每股计
 *
 * <p>3) 仓位管理 Position = accountCapital * riskPercent / (entry - stopLoss) A 股最小交易单位 100 股,实际可买 =
 * floor(shares / 100) * 100
 *
 * <p>参考 Mark Minervini / Jesse Livermore / Michael Marcus 的纪律: - 风报比 ≥ 1:3 才进场 - 单笔风险 ≤ 账户 1-2% -
 * 仓位一致,不因把握度而放大
 */
@Service
public class PositionManagementService {

  private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
  private static final int DEFAULT_DRAWDOWN_ROWS = 12;

  public PositionAdviceDTO advise(PositionAdviceRequest req) {
    if (req == null) {
      throw new IllegalArgumentException("请求不能为空");
    }
    BigDecimal account = nz(req.getAccountCapital(), "账户资金");
    BigDecimal entry = nz(req.getEntryPrice(), "入场价");
    BigDecimal stop = nz(req.getStopLossPrice(), "止损价");
    BigDecimal target = nz(req.getTargetPrice(), "目标价");

    // 入场价必须大于止损价、止损价必须小于目标价,否则仓位公式无意义
    if (entry.compareTo(stop) <= 0) {
      throw new IllegalArgumentException("入场价必须大于止损价");
    }
    if (target.compareTo(entry) <= 0) {
      throw new IllegalArgumentException("目标价必须大于入场价");
    }

    BigDecimal riskPct =
        req.getRiskPercent() == null ? new BigDecimal("0.01") : req.getRiskPercent();
    BigDecimal winRate = req.getWinRate() == null ? new BigDecimal("0.40") : req.getWinRate();

    // —— 公式 1:仓位管理 ——
    BigDecimal riskPerShare = entry.subtract(stop);
    BigDecimal maxRiskAmount = account.multiply(riskPct).setScale(2, RoundingMode.HALF_UP);
    BigDecimal sharesRaw = maxRiskAmount.divide(riskPerShare, 4, RoundingMode.DOWN);
    BigDecimal lots =
        sharesRaw.divide(ONE_HUNDRED, 0, RoundingMode.DOWN).setScale(0, RoundingMode.DOWN);
    BigDecimal actualShares = lots.multiply(ONE_HUNDRED);
    BigDecimal positionAmount = actualShares.multiply(entry).setScale(2, RoundingMode.HALF_UP);
    BigDecimal positionPct = positionAmount.divide(account, 4, RoundingMode.HALF_UP);

    PositionAdviceDTO.Sizing sizing =
        PositionAdviceDTO.Sizing.builder()
            .riskPerShare(riskPerShare.setScale(2, RoundingMode.HALF_UP))
            .maxRiskAmount(maxRiskAmount)
            .shares(actualShares)
            .positionAmount(positionAmount)
            .positionPct(positionPct)
            .lotsOf100(lots)
            .build();

    // —— 公式 2:风险回报比 ——
    BigDecimal rewardPerShare = target.subtract(entry);
    BigDecimal ratioValue = rewardPerShare.divide(riskPerShare, 4, RoundingMode.HALF_UP);
    BigDecimal breakEven =
        BigDecimal.ONE.divide(BigDecimal.ONE.add(ratioValue), 4, RoundingMode.HALF_UP);
    String ratioLabel = String.format("1 : %s", stripTrailing(ratioValue));
    String rrVerdict;
    String rrReason;
    if (ratioValue.compareTo(BigDecimal.valueOf(3)) >= 0) {
      rrVerdict = "excellent";
      rrReason = "≥ 1:3,达到 Mark Minervini 的最低进场门槛,具备显著统计优势。";
    } else if (ratioValue.compareTo(BigDecimal.valueOf(2)) >= 0) {
      rrVerdict = "good";
      rrReason = "1:2 ~ 1:3,具备统计优势;若胜率 < 33%,长期将亏损。";
    } else if (ratioValue.compareTo(BigDecimal.valueOf(1)) >= 0) {
      rrVerdict = "marginal";
      rrReason = "1:1 ~ 1:2,接近盈亏平衡;风报优势微弱,需要较高胜率才能盈利。";
    } else {
      rrVerdict = "poor";
      rrReason = "< 1:1,即使 60% 胜率也长期亏钱;典型散户「赚一点就跑、亏了死扛」的处境。";
    }
    PositionAdviceDTO.RiskReward riskReward =
        PositionAdviceDTO.RiskReward.builder()
            .rewardPerShare(rewardPerShare.setScale(2, RoundingMode.HALF_UP))
            .ratioLabel(ratioLabel)
            .ratioValue(ratioValue)
            .breakEvenWinRate(breakEven)
            .verdict(rrVerdict)
            .verdictReason(rrReason)
            .build();

    // —— 公式 3:期望值 ——
    // 以「每股」为单位计算:avgWin = rewardPerShare,avgLoss = riskPerShare
    BigDecimal evPerShare =
        winRate
            .multiply(rewardPerShare)
            .subtract(BigDecimal.ONE.subtract(winRate).multiply(riskPerShare));
    BigDecimal expectedValuePerTrade =
        evPerShare.multiply(actualShares).setScale(2, RoundingMode.HALF_UP);
    BigDecimal expectedValue100 =
        expectedValuePerTrade.multiply(ONE_HUNDRED).setScale(2, RoundingMode.HALF_UP);
    String evVerdict;
    if (evPerShare.compareTo(BigDecimal.ZERO) > 0) {
      evVerdict = "positive";
    } else if (evPerShare.compareTo(BigDecimal.ZERO) < 0) {
      evVerdict = "negative";
    } else {
      evVerdict = "breakeven";
    }
    PositionAdviceDTO.Expectation expectation =
        PositionAdviceDTO.Expectation.builder()
            .winRate(winRate)
            .avgWin(rewardPerShare.setScale(2, RoundingMode.HALF_UP))
            .avgLoss(riskPerShare.setScale(2, RoundingMode.HALF_UP))
            .expectedValuePerTrade(expectedValuePerTrade)
            .expectedValue100Trades(expectedValue100)
            .verdict(evVerdict)
            .build();

    // —— 附加:连续亏损场景回撤 ——
    List<PositionAdviceDTO.DrawdownRow> drawdowns = buildDrawdownTable(riskPct);

    // —— 结论与建议 ——
    String verdict = composeVerdict(rrVerdict, evVerdict, ratioValue, breakEven, winRate);
    String summary = composeSummary(account, riskPct, ratioValue, breakEven, winRate);
    List<String> principles = composePrinciples(rrVerdict, evVerdict, lots, positionPct);

    PositionAdviceDTO.InputSummary inputSummary =
        PositionAdviceDTO.InputSummary.builder()
            .stockKeyword(req.getStockKeyword())
            .accountCapital(account)
            .entryPrice(entry)
            .stopLossPrice(stop)
            .targetPrice(target)
            .riskPercent(riskPct)
            .winRate(winRate)
            .build();

    return PositionAdviceDTO.builder()
        .input(inputSummary)
        .sizing(sizing)
        .riskReward(riskReward)
        .expectation(expectation)
        .drawdownTable(drawdowns)
        .verdict(verdict)
        .summary(summary)
        .principles(principles)
        .build();
  }

  public List<LegendDTO> legends() {
    return LegendDTO.all();
  }

  public List<LegendDTO.RiskRewardPreset> presets() {
    return LegendDTO.presets();
  }

  // —— helpers ——

  private List<PositionAdviceDTO.DrawdownRow> buildDrawdownTable(BigDecimal riskPct) {
    // 模拟「连续 N 次都输」的最坏情况:账户剩余 = (1 - riskPct)^N
    List<PositionAdviceDTO.DrawdownRow> rows = new ArrayList<>();
    BigDecimal oneMinus = BigDecimal.ONE.subtract(riskPct);
    for (int n = 1; n <= DEFAULT_DRAWDOWN_ROWS; n++) {
      BigDecimal remaining = oneMinus.pow(n).setScale(4, RoundingMode.HALF_UP);
      // 恢复所需盈利率 = (1 / remaining) - 1
      BigDecimal recover =
          BigDecimal.ONE
              .divide(remaining, 4, RoundingMode.HALF_UP)
              .subtract(BigDecimal.ONE)
              .setScale(4, RoundingMode.HALF_UP);
      rows.add(
          PositionAdviceDTO.DrawdownRow.builder()
              .consecutiveLosses(n)
              .remainingPct(remaining)
              .recoverGainRequired(recover)
              .build());
    }
    return rows;
  }

  private String composeVerdict(
      String rrVerdict,
      String evVerdict,
      BigDecimal ratioValue,
      BigDecimal breakEven,
      BigDecimal winRate) {
    StringBuilder sb = new StringBuilder();
    // 多个判定信号叠加,避免互相覆盖
    if ("negative".equals(evVerdict)) {
      sb.append("⚠️ 负期望:长期必输市场,即使单笔风报比看着不错,也应放弃或修正系统。\n");
    }
    if ("poor".equals(rrVerdict)) {
      sb.append("⛔ 风报比低于 1:1,典型「赚少亏多」结构,坚决回避。\n");
    }
    if ("excellent".equals(rrVerdict) && "positive".equals(evVerdict)) {
      sb.append("✅ 三公式同时通过:风报比优秀且期望值为正,可以进场。");
    } else if (winRate.compareTo(breakEven) < 0) {
      sb.append("⚠️ 当前风报比下,你的胜率 (")
          .append(stripTrailing(winRate.multiply(BigDecimal.valueOf(100))))
          .append("%) 低于盈亏平衡所需 (")
          .append(stripTrailing(breakEven.multiply(BigDecimal.valueOf(100))))
          .append("%),需要更高胜率或更优进场点。");
    } else if (!"negative".equals(evVerdict) && !"poor".equals(rrVerdict)) {
      sb.append("🟡 边缘方案:风报比可接受,但期望值不显著,建议缩小仓位或抬高止损距离。");
    }
    return sb.toString().replaceAll("\\s*\\n+\\s*$", "");
  }

  private String composeSummary(
      BigDecimal account,
      BigDecimal riskPct,
      BigDecimal ratioValue,
      BigDecimal breakEven,
      BigDecimal winRate) {
    return String.format(
        "以 %s 元账户、单笔风险 %s%% 计算,本笔最大可承受亏损 ≈ %s 元;当前风报比 1:%s,盈亏平衡胜率 %s%%;你的估计胜率 %s%%。",
        formatMoney(account),
        stripTrailing(riskPct.multiply(BigDecimal.valueOf(100))),
        formatMoney(account.multiply(riskPct).setScale(2, RoundingMode.HALF_UP)),
        stripTrailing(ratioValue),
        stripTrailing(breakEven.multiply(BigDecimal.valueOf(100))),
        stripTrailing(winRate.multiply(BigDecimal.valueOf(100))));
  }

  private List<String> composePrinciples(
      String rrVerdict, String evVerdict, BigDecimal lots, BigDecimal positionPct) {
    List<String> ps = new ArrayList<>();
    ps.add("公式 1 — 仓位管理:Position = 账户 × 风险比例 ÷ (入场价 − 止损价)");
    ps.add("公式 2 — 风险回报比:R:R = (入场 − 止损) : (目标 − 入场),盈亏平衡胜率 = 1 / (1 + R)");
    ps.add("公式 3 — 期望值:EV = 胜率 × 平均盈利 − 败率 × 平均亏损");
    if ("excellent".equals(rrVerdict)) {
      ps.add("Mark Minervini 原则满足:风报比 ≥ 1:3,具备统计优势。");
    } else {
      ps.add("未达到 Minervini 的 1:3 门槛,请重新评估进场点或等待更好风险回报结构。");
    }
    if (lots.compareTo(BigDecimal.ZERO) == 0) {
      ps.add("⚠️ 风险预算不足以买入最小 100 单位的仓位,请提高账户资金或放宽止损距离。");
    } else {
      ps.add(
          String.format(
              "实际建议买入 %s 手(共 %s 股),占账户 %s%%。",
              stripTrailing(lots),
              stripTrailing(lots.multiply(ONE_HUNDRED)),
              stripTrailing(positionPct.multiply(BigDecimal.valueOf(100)))));
    }
    if ("positive".equals(evVerdict)) {
      ps.add("期望值为正:连续执行 100 笔,期望累计盈利可见(见下方报告)。");
    } else {
      ps.add("期望值非正:任何「靠感觉」的优化都不能让负期望值系统赚钱,需要先修正进场逻辑。");
    }
    ps.add("Michael Marcus 纪律:再有把握也不放大仓位,保持风险一致性。");
    ps.add("Livermore 心法:让利润奔跑,但用止损保护本金 — 亏小赚大是复利的核心。");
    return ps;
  }

  private static BigDecimal nz(BigDecimal v, String name) {
    if (v == null) {
      throw new IllegalArgumentException(name + " 不能为空");
    }
    return v;
  }

  private static String stripTrailing(BigDecimal v) {
    if (v == null) return "-";
    return v.stripTrailingZeros().toPlainString();
  }

  private static String formatMoney(BigDecimal v) {
    if (v == null) return "-";
    return String.format("%,.2f", v.doubleValue());
  }
}
