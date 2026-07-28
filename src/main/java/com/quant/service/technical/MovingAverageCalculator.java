package com.quant.service.technical;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.quant.entity.TradeStockDaily;

/** 纯函数均线/量能计算工具，输入日 K 按日期升序更稳妥，降序也可。 */
public final class MovingAverageCalculator {

  private MovingAverageCalculator() {}

  public record MovingAverages(
      BigDecimal ma5,
      BigDecimal ma10,
      BigDecimal ma20,
      BigDecimal ma60,
      BigDecimal ma20Slope,
      BigDecimal prevMa10,
      BigDecimal prevMa20,
      Long volMa5,
      Long volMa20,
      BigDecimal volRatio,
      BigDecimal latestClose,
      Long latestVolume) {

    public boolean bullishAlignment() {
      return positive(ma5)
          && positive(ma10)
          && positive(ma20)
          && positive(ma60)
          && ma5.compareTo(ma10) > 0
          && ma10.compareTo(ma20) > 0
          && ma20.compareTo(ma60) > 0;
    }

    public boolean aboveMa20() {
      return positive(latestClose) && positive(ma20) && latestClose.compareTo(ma20) > 0;
    }

    public boolean ma20Rising() {
      return ma20Slope != null && ma20Slope.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean deathCross10_20() {
      return positive(prevMa10)
          && positive(prevMa20)
          && positive(ma10)
          && positive(ma20)
          && prevMa10.compareTo(prevMa20) >= 0
          && ma10.compareTo(ma20) < 0;
    }
  }

  public static MovingAverages fromDaily(List<TradeStockDaily> daily) {
    if (daily == null || daily.isEmpty()) {
      return empty();
    }
    List<TradeStockDaily> asc = sortedAsc(daily);
    int n = asc.size();
    TradeStockDaily latest = asc.get(n - 1);
    BigDecimal ma5 = smaClose(asc, 5);
    BigDecimal ma10 = smaClose(asc, 10);
    BigDecimal ma20 = smaClose(asc, 20);
    BigDecimal ma60 = smaClose(asc, 60);
    BigDecimal prevMa10 = smaClose(asc.subList(0, Math.max(0, n - 1)), 10);
    BigDecimal prevMa20 = smaClose(asc.subList(0, Math.max(0, n - 1)), 20);
    BigDecimal ma20Prev5 = null;
    if (n > 5) {
      ma20Prev5 = smaClose(asc.subList(0, n - 5), 20);
    }
    BigDecimal slope = null;
    if (positive(ma20) && positive(ma20Prev5)) {
      slope =
          ma20.subtract(ma20Prev5).divide(ma20Prev5, 6, RoundingMode.HALF_UP);
    }
    Long volMa5 = smaVolume(asc, 5);
    Long volMa20 = smaVolume(asc, 20);
    BigDecimal volRatio = volumeExpandRatio(asc, 20, 20);
    return new MovingAverages(
        ma5,
        ma10,
        ma20,
        ma60,
        slope,
        prevMa10,
        prevMa20,
        volMa5,
        volMa20,
        volRatio,
        latest.getClosePrice(),
        latest.getVolume());
  }

  /** 近 recent 日均量 / 再往前 base 日均量。 */
  public static BigDecimal volumeExpandRatio(List<TradeStockDaily> daily, int recent, int base) {
    List<TradeStockDaily> asc = sortedAsc(daily);
    if (asc.size() < recent + base) {
      return null;
    }
    long recentSum = 0;
    long baseSum = 0;
    int n = asc.size();
    for (int i = n - recent; i < n; i++) {
      recentSum += safeVol(asc.get(i));
    }
    for (int i = n - recent - base; i < n - recent; i++) {
      baseSum += safeVol(asc.get(i));
    }
    if (baseSum <= 0) {
      return null;
    }
    return BigDecimal.valueOf(recentSum)
        .divide(BigDecimal.valueOf(baseSum), 4, RoundingMode.HALF_UP);
  }

  public static BigDecimal highNearRatio(List<TradeStockDaily> daily, int lookbackDays) {
    List<TradeStockDaily> asc = sortedAsc(daily);
    if (asc.isEmpty()) {
      return null;
    }
    int from = Math.max(0, asc.size() - lookbackDays);
    BigDecimal maxHigh = null;
    for (int i = from; i < asc.size(); i++) {
      BigDecimal h = asc.get(i).getHighPrice();
      if (h == null) continue;
      if (maxHigh == null || h.compareTo(maxHigh) > 0) {
        maxHigh = h;
      }
    }
    BigDecimal close = asc.get(asc.size() - 1).getClosePrice();
    if (!positive(maxHigh) || !positive(close)) {
      return null;
    }
    return close.divide(maxHigh, 4, RoundingMode.HALF_UP);
  }

  public static BigDecimal smaClose(List<TradeStockDaily> asc, int period) {
    if (asc == null || asc.size() < period || period <= 0) {
      return null;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = asc.size() - period; i < asc.size(); i++) {
      BigDecimal c = asc.get(i).getClosePrice();
      if (c == null) {
        return null;
      }
      sum = sum.add(c);
    }
    return sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);
  }

  public static Long smaVolume(List<TradeStockDaily> asc, int period) {
    if (asc == null || asc.size() < period || period <= 0) {
      return null;
    }
    long sum = 0;
    for (int i = asc.size() - period; i < asc.size(); i++) {
      sum += safeVol(asc.get(i));
    }
    return sum / period;
  }

  public static List<TradeStockDaily> sortedAsc(List<TradeStockDaily> daily) {
    List<TradeStockDaily> asc = new ArrayList<>(daily);
    asc.sort(
        Comparator.comparing(
            TradeStockDaily::getTradeDate, Comparator.nullsLast(Comparator.naturalOrder())));
    return asc;
  }

  private static MovingAverages empty() {
    return new MovingAverages(null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static long safeVol(TradeStockDaily d) {
    return d == null || d.getVolume() == null ? 0L : d.getVolume();
  }

  private static boolean positive(BigDecimal v) {
    return v != null && v.compareTo(BigDecimal.ZERO) > 0;
  }
}
