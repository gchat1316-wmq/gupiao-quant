package com.quant.service.etfmodel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 省心 ETF 纯规则引擎 — 无状态、无外部依赖，输入持仓快照/行情/均线，输出信号列表。
 *
 * <p>模型阈值是【省心】交易模型的固定纪律，故为常量而非配置：
 *
 * <ul>
 *   <li>止盈：+5% 减 1/3 → +10% 再减 1/3 → 剩 1/3 移动止盈（收盘跌破 20 日线才卖）
 *   <li>止损：宽基 -15% 减半 → -30% 再减半留 1/4；行业 -10% 减半 → -18% 无条件清仓
 * </ul>
 */
@Component
public class EtfSignalEngine {

  static final BigDecimal TP1_PCT = BigDecimal.valueOf(5);
  static final BigDecimal TP2_PCT = BigDecimal.valueOf(10);
  static final BigDecimal BROAD_SL1_PCT = BigDecimal.valueOf(-15);
  static final BigDecimal BROAD_SL2_PCT = BigDecimal.valueOf(-30);
  static final BigDecimal SECTOR_SL1_PCT = BigDecimal.valueOf(-10);
  static final BigDecimal SECTOR_SL2_PCT = BigDecimal.valueOf(-18);

  /** 建议买入档位 */
  public enum BuyTier {
    /** 轻仓 ≤5000（趋势向下/涨幅大） */
    LIGHT,
    /** 中仓 10000~20000（横盘/趋势向上） */
    MID
  }

  public record TrendAdvice(String trend, BuyTier tier, String reason) {}

  /** 盘中/收盘阈值检查：止盈两档 + 止损两档。每档触发后由 done 标志抑制重复。 */
  public List<EtfSignal> evaluateThresholds(EtfPositionView pos, BigDecimal latestPrice) {
    List<EtfSignal> out = new ArrayList<>();
    BigDecimal profit = pos.profitPct(latestPrice);
    if (profit == null) {
      return out;
    }

    if (!pos.isTp1Done() && profit.compareTo(TP1_PCT) >= 0) {
      out.add(
          signal(
              pos,
              EtfSignal.TP1,
              String.format("💰 %s(%s) 收益 %s%% 达 +5%%，建议减 1/3", pos.getStockName(), pos.getStockCode(), profit),
              thresholdContent(pos, latestPrice, profit, "止盈第一档 +5%", "卖出 1/3 仓位（剩余进入 +10% 档监控）"),
              latestPrice,
              false));
    } else if (pos.isTp1Done() && !pos.isTp2Done() && profit.compareTo(TP2_PCT) >= 0) {
      out.add(
          signal(
              pos,
              EtfSignal.TP2,
              String.format("💰 %s(%s) 收益 %s%% 达 +10%%，建议再减 1/3", pos.getStockName(), pos.getStockCode(), profit),
              thresholdContent(pos, latestPrice, profit, "止盈第二档 +10%", "再卖出 1/3 仓位（剩余 1/3 移动止盈：收盘跌破 20 日线才卖）"),
              latestPrice,
              false));
    }

    if (pos.isBroad()) {
      if (!pos.isSl1Done() && profit.compareTo(BROAD_SL1_PCT) <= 0) {
        out.add(
            signal(
                pos,
                EtfSignal.SL1,
                String.format("🛑 %s(%s) 亏损 %s%% 触及宽基止损 -15%%，建议减半", pos.getStockName(), pos.getStockCode(), profit),
                thresholdContent(pos, latestPrice, profit, "宽基止损第一档 -15%", "卖出 1/2 仓位；剩余待 -30% 档，减仓后周K平稳可回补"),
                latestPrice,
                false));
      } else if (pos.isSl1Done() && !pos.isSl2Done() && profit.compareTo(BROAD_SL2_PCT) <= 0) {
        out.add(
            signal(
                pos,
                EtfSignal.SL2,
                String.format("🛑 %s(%s) 亏损 %s%% 触及宽基止损 -30%%，建议再减半", pos.getStockName(), pos.getStockCode(), profit),
                thresholdContent(pos, latestPrice, profit, "宽基止损第二档 -30%", "再卖出 1/2，留 1/4 长持，平稳后回补"),
                latestPrice,
                false));
      }
    } else {
      // 行业/主题：-18% 无条件清仓（不要求先减半），防行业逻辑永久破坏
      if (!pos.isSl2Done() && profit.compareTo(SECTOR_SL2_PCT) <= 0) {
        out.add(
            signal(
                pos,
                EtfSignal.SL2,
                String.format("🛑 %s(%s) 亏损 %s%% 触及行业止损 -18%%，无条件清仓", pos.getStockName(), pos.getStockCode(), profit),
                thresholdContent(pos, latestPrice, profit, "行业止损第二档 -18%", "无条件清仓（防行业逻辑永久破坏，如教育、地产）"),
                latestPrice,
                false));
      } else if (!pos.isSl1Done() && profit.compareTo(SECTOR_SL1_PCT) <= 0) {
        out.add(
            signal(
                pos,
                EtfSignal.SL1,
                String.format("🛑 %s(%s) 亏损 %s%% 触及行业止损 -10%%，建议减半", pos.getStockName(), pos.getStockCode(), profit),
                thresholdContent(pos, latestPrice, profit, "行业止损第一档 -10%", "卖出 1/2 仓位；-18% 无条件清仓，减仓后周K平稳可回补"),
                latestPrice,
                false));
      }
    }
    return out;
  }

  /** 移动止盈（仅收盘判定）：已完成两档止盈的剩余仓位，收盘跌破 20 日线 → 清仓。 */
  public EtfSignal evaluateTrailExit(EtfPositionView pos, BigDecimal close, BigDecimal ma20) {
    if (pos.getShares() <= 0
        || !pos.isTp2Done()
        || close == null
        || ma20 == null
        || close.compareTo(ma20) >= 0) {
      return null;
    }
    return signal(
        pos,
        EtfSignal.TRAIL_EXIT,
        String.format("📉 %s(%s) 收盘跌破 20 日线，移动止盈离场", pos.getStockName(), pos.getStockCode()),
        "## "
            + pos.getStockName()
            + "（"
            + pos.getStockCode()
            + "）\n\n- 收盘价: "
            + close
            + "\n- 20 日均线: "
            + ma20
            + "\n- 规则: 剩余 1/3 移动止盈，收盘跌破 20 日线才卖 ✅ 已触发\n- 建议: 卖出剩余全部仓位，锁定利润",
        close,
        false);
  }

  /** 组合级保命线：总资产从最高点回撤 ≥ 阈值 → 整体降 1/4 + 冷静一周。 */
  public EtfSignal evaluatePortfolioGuard(
      BigDecimal totalAsset, BigDecimal peakAsset, BigDecimal drawdownThresholdPct, int calmDays) {
    BigDecimal dd = drawdownPct(totalAsset, peakAsset);
    if (dd == null || dd.compareTo(drawdownThresholdPct) < 0) {
      return null;
    }
    return EtfSignal.builder()
        .signalType(EtfSignal.PORTFOLIO_GUARD)
        .title(String.format("🚨 组合总资产回撤 %s%%，触发保命线：整体降 1/4", dd))
        .content(
            "## 组合级保命线触发\n\n- 总资产: "
                + totalAsset
                + "\n- 历史最高: "
                + peakAsset
                + "\n- 回撤: "
                + dd
                + "%（阈值 "
                + drawdownThresholdPct.stripTrailingZeros().toPlainString()
                + "%）\n- 建议: 所有持仓整体降 1/4，冷静 "
                + calmDays
                + " 天\n- 冷静期内买入/加仓提醒将附加冷静标注")
        .triggeredAt(LocalDateTime.now())
        .buyAdvice(false)
        .build();
  }

  /** 回补条件（周五收盘判定）：周收盘价站上日线 5 日均线。连续 2 周满足由服务层累计。 */
  public boolean weeklyCloseAboveMa5(BigDecimal weeklyClose, BigDecimal ma5) {
    return weeklyClose != null && ma5 != null && weeklyClose.compareTo(ma5) > 0;
  }

  /** 可回补信号（recoup_weeks 达标后由服务层调用生成）。 */
  public EtfSignal recoupReady(EtfPositionView pos, BigDecimal close, BigDecimal ma5) {
    return signal(
        pos,
        EtfSignal.RECOUP_READY,
        String.format("🔄 %s(%s) 周K连续 2 周站上 5 日线，可回补", pos.getStockName(), pos.getStockCode()),
        "## "
            + pos.getStockName()
            + "（"
            + pos.getStockCode()
            + "）\n\n- 周收盘价: "
            + close
            + "\n- 5 日均线: "
            + ma5
            + "\n- 规则: 止损减仓后，周K连续 2 周收在 5 日线上方 → 可回补 ✅\n- 建议: 按分批规则回补（轻仓/中仓按趋势判档）",
        close,
        true);
  }

  /**
   * 趋势判档（选 B）：趋势向下/近20日涨幅大 → 轻仓 ≤5000；横盘/趋势向上 → 中仓 10000~20000。
   *
   * @param rise20Pct 近 20 日涨幅(%)，可为 null
   */
  public TrendAdvice trendAdvice(
      BigDecimal close,
      BigDecimal ma5,
      BigDecimal ma20,
      BigDecimal ma20Slope,
      BigDecimal rise20Pct,
      BigDecimal bigRiseThresholdPct) {
    if (close == null || ma5 == null || ma20 == null) {
      return new TrendAdvice("UNKNOWN", null, "日K数据不足，无法判档");
    }
    String trend;
    if (close.compareTo(ma20) < 0 && ma5.compareTo(ma20) < 0) {
      trend = "DOWN";
    } else if (ma5.compareTo(ma20) > 0
        && ma20Slope != null
        && ma20Slope.compareTo(BigDecimal.ZERO) > 0) {
      trend = "UP";
    } else {
      trend = "FLAT";
    }
    boolean bigRise =
        rise20Pct != null
            && bigRiseThresholdPct != null
            && rise20Pct.compareTo(bigRiseThresholdPct) >= 0;
    if ("DOWN".equals(trend)) {
      return new TrendAdvice(trend, BuyTier.LIGHT, "趋势向下（MA5、价格均在 20 日线下方）→ 轻仓");
    }
    if (bigRise) {
      return new TrendAdvice(
          trend, BuyTier.LIGHT, "近 20 日涨幅 " + rise20Pct + "% 偏大（≥" + bigRiseThresholdPct + "%）→ 轻仓");
    }
    return new TrendAdvice(
        trend, BuyTier.MID, ("UP".equals(trend) ? "趋势向上" : "横盘") + " 且涨幅不大 → 中仓");
  }

  public static BigDecimal drawdownPct(BigDecimal totalAsset, BigDecimal peakAsset) {
    if (totalAsset == null || peakAsset == null || peakAsset.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return peakAsset
        .subtract(totalAsset)
        .divide(peakAsset, 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private EtfSignal signal(
      EtfPositionView pos,
      String type,
      String title,
      String content,
      BigDecimal triggerPrice,
      boolean buyAdvice) {
    return EtfSignal.builder()
        .stockCode(pos.getStockCode())
        .stockName(pos.getStockName())
        .signalType(type)
        .title(title)
        .content(content)
        .triggerPrice(triggerPrice)
        .triggeredAt(LocalDateTime.now())
        .buyAdvice(buyAdvice)
        .build();
  }

  private String thresholdContent(
      EtfPositionView pos, BigDecimal latest, BigDecimal profit, String rule, String action) {
    return "## "
        + pos.getStockName()
        + "（"
        + pos.getStockCode()
        + "）\n\n- 当前价: "
        + latest
        + "\n- 摊薄成本: "
        + pos.getDilutedCost()
        + "\n- 收益率: "
        + profit
        + "%\n- 规则: "
        + rule
        + " ✅ 已触发\n- 建议: "
        + action;
  }
}
