package com.quant.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

import com.quant.entity.TradeStockBasic;

/**
 * 10×PS 估值计算：三档判定（低估 / 合理 / 泡沫）+ 偏离百分比，以及 YTD 涨幅、总市值等派生指标。
 *
 * <p>估值算法集中在此，前端与后端共用同一份结论，避免"各算一份"导致算法漂移。 {@link #inferValuationRange} 为纯函数（静态），便于单测直接调用。
 */
@Service
public class InvestValuationService {

  /**
   * 10×PS 估值三档的判定结果：
   *
   * <ul>
   *   <li>level — 低估 / 合理 / 泡沫 三档标签，或 null（无任何 Y 数据）
   *   <li>degree — 偏离参考年 10×PS 的百分比（−100~+∞），仅低估/泡沫给出
   *   <li>refYear — 参考年（2027=forecastY1 用作低估对照，2028=forecastY2 用作泡沫对照）
   * </ul>
   *
   * 同一个函数同时返回三段，避免前端/后端各算一份导致算法漂移。
   */
  public record ValuationVerdict(String level, BigDecimal degree, Integer refYear) {
    public static final ValuationVerdict EMPTY = new ValuationVerdict(null, null, null);
  }

  /**
   * 10×PS 估值三档 + 偏离百分比。
   *
   * <pre>
   *   合理市值   = 未来第 N 年预测营收 × 10
   *   低估       marketCap < Y1 × 10            → 27 -X%（X = mc/(Y1×10) - 1）
   *   泡沫       marketCap > Y2 × 10            → 28 +X%
   *   合理       Y1×10 ≤ mc ≤ Y2×10            → refYear=距 Y1/Y2 更近那年，degree=mc/ref-1
   * </pre>
   *
   * 短差用 Y1 拉得紧，长差用 Y2 给发展空间 —— 两套参照年分开避免单一指标拍脑袋。
   *
   * <p>2026-07-01 改：合理时也输出偏离，让用户知道是"刚过 Y1"（接近低估） 还是"快到 Y2"（接近泡沫）。思泰克 mc=113.7 vs Y2=118 只差 3.6%，
   * 显示"合理 28年 -3.64%"比裸"合理"直观得多。
   */
  public static ValuationVerdict inferValuationRange(
      BigDecimal marketCap, BigDecimal revenueForecastY1, BigDecimal revenueForecastY2) {
    if (marketCap == null) return ValuationVerdict.EMPTY;
    BigDecimal fairCapY1 =
        revenueForecastY1 == null ? null : revenueForecastY1.multiply(BigDecimal.TEN);
    BigDecimal fairCapY2 =
        revenueForecastY2 == null ? null : revenueForecastY2.multiply(BigDecimal.TEN);

    if (fairCapY1 != null && marketCap.compareTo(fairCapY1) < 0) {
      return new ValuationVerdict("低估", degreeAgainst(marketCap, fairCapY1), 2027);
    }
    if (fairCapY2 != null && marketCap.compareTo(fairCapY2) > 0) {
      return new ValuationVerdict("泡沫", degreeAgainst(marketCap, fairCapY2), 2028);
    }
    if (fairCapY1 == null && fairCapY2 == null) {
      return ValuationVerdict.EMPTY;
    }

    // 合理区间：refYear = 相对距离 Y1/Y2 更近的那年（除以 fairCap 归一化）。
    // 思泰克: 相对距 Y1=22.9/90.8=25.2%, 相对距 Y2=4.3/118=3.6% → 选 Y2 → degree=-3.64%
    // 中点 mc=250, fairCapY1=200, fairCapY2=300: 相对距 Y1=50/200=25%, 相对距 Y2=50/300=16.7% → 选 Y2
    // 完全相等时默认选 Y1。
    BigDecimal distY1 =
        fairCapY1 == null
            ? null
            : marketCap.subtract(fairCapY1).abs().divide(fairCapY1, 6, RoundingMode.HALF_UP);
    BigDecimal distY2 =
        fairCapY2 == null
            ? null
            : marketCap.subtract(fairCapY2).abs().divide(fairCapY2, 6, RoundingMode.HALF_UP);

    if (fairCapY1 == null) {
      return new ValuationVerdict("合理", degreeAgainst(marketCap, fairCapY2), 2028);
    }
    if (fairCapY2 == null) {
      return new ValuationVerdict("合理", degreeAgainst(marketCap, fairCapY1), 2027);
    }
    if (distY1.compareTo(distY2) <= 0) {
      return new ValuationVerdict("合理", degreeAgainst(marketCap, fairCapY1), 2027);
    }
    return new ValuationVerdict("合理", degreeAgainst(marketCap, fairCapY2), 2028);
  }

  /**
   * 当前市值相对 fairCap 偏离百分比 = mc / fairCap - 1，结果乘 100 取两位小数。 例：mc=150, fairCap=100 → +50.00；mc=50,
   * fairCap=100 → -50.00。
   */
  private static BigDecimal degreeAgainst(BigDecimal marketCap, BigDecimal fairCap) {
    BigDecimal ratio = marketCap.divide(fairCap, 6, RoundingMode.HALF_UP);
    BigDecimal percent = ratio.multiply(BigDecimal.valueOf(100)).subtract(BigDecimal.valueOf(100));
    return percent.setScale(2, RoundingMode.HALF_UP);
  }

  /** 年初至今涨幅 (%)。缺 latestPrice / yearStartClose 或年初收盘为 0 时返回 null。 */
  public BigDecimal computeYtdGain(BigDecimal latestPrice, BigDecimal yearStartClose) {
    if (latestPrice == null
        || yearStartClose == null
        || yearStartClose.compareTo(BigDecimal.ZERO) == 0) return null;
    return latestPrice
        .subtract(yearStartClose)
        .divide(yearStartClose, 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP);
  }

  /** 总市值（亿元）= 总股本 × 最新价 / 1e8。缺价或总股本时返回 null。 */
  public BigDecimal computeMarketCap(BigDecimal latestPrice, TradeStockBasic basic) {
    if (latestPrice == null || basic == null || basic.getTotalShares() == null) return null;
    BigDecimal totalShares = BigDecimal.valueOf(basic.getTotalShares());
    BigDecimal totalCap = totalShares.multiply(latestPrice);
    return totalCap.divide(BigDecimal.valueOf(100_000_000L), 2, RoundingMode.HALF_UP);
  }
}
