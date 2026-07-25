package com.quant.service.swing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;

import com.quant.entity.TradeStockDaily;
import com.quant.repository.TradeStockDailyRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SwingIndicatorService {

  private final TradeStockDailyRepository dailyRepository;

  public record MaSnapshot(
      BigDecimal ma5,
      BigDecimal ma10,
      BigDecimal ma20,
      BigDecimal ma60,
      BigDecimal ma20Slope,
      Long volMa20,
      Long volMa60,
      BigDecimal volRatio,
      BigDecimal latestClose,
      boolean bullishAligned,
      boolean aboveMa20,
      boolean ma20Rising) {}

  /** 返回按日期升序的最近 bars 条日 K（不足则返回已有）。 */
  public List<TradeStockDaily> loadAsc(String stockCode, int bars) {
    List<TradeStockDaily> desc =
        dailyRepository.findTop80ByStockCodeOrderByTradeDateDesc(stockCode);
    if (desc.isEmpty()) {
      // 兼容无后缀代码
      String bare = SwingCodeUtils.bareCode(stockCode);
      if (!bare.equals(stockCode)) {
        desc = dailyRepository.findTop80ByStockCodeOrderByTradeDateDesc(bare);
      }
    }
    if (desc.isEmpty()) {
      return List.of();
    }
    int n = Math.min(bars, desc.size());
    List<TradeStockDaily> slice = new ArrayList<>(desc.subList(0, n));
    Collections.reverse(slice);
    return slice;
  }

  public MaSnapshot compute(List<TradeStockDaily> asc) {
    if (asc == null || asc.size() < 60) {
      return null;
    }
    BigDecimal ma5 = avgClose(asc, 5);
    BigDecimal ma10 = avgClose(asc, 10);
    BigDecimal ma20 = avgClose(asc, 20);
    BigDecimal ma60 = avgClose(asc, 60);
    BigDecimal prevMa20 = avgClose(asc.subList(0, asc.size() - 1), 20);
    BigDecimal slope =
        prevMa20 == null || prevMa20.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : ma20.subtract(prevMa20).divide(prevMa20, 6, RoundingMode.HALF_UP);
    Long volMa20 = avgVol(asc, 20);
    Long volMa60 = avgVol(asc, 60);
    BigDecimal volRatio =
        volMa60 == null || volMa60 == 0
            ? null
            : BigDecimal.valueOf(volMa20)
                .divide(BigDecimal.valueOf(volMa60), 4, RoundingMode.HALF_UP);
    BigDecimal latest = asc.get(asc.size() - 1).getClosePrice();
    boolean aligned =
        ma5 != null
            && ma10 != null
            && ma20 != null
            && ma60 != null
            && ma5.compareTo(ma10) > 0
            && ma10.compareTo(ma20) > 0
            && ma20.compareTo(ma60) > 0;
    boolean above = latest != null && ma20 != null && latest.compareTo(ma20) >= 0;
    boolean rising = slope != null && slope.compareTo(BigDecimal.ZERO) > 0;
    return new MaSnapshot(
        ma5, ma10, ma20, ma60, slope, volMa20, volMa60, volRatio, latest, aligned, above, rising);
  }

  public boolean isLimitUp(
      TradeStockDaily cur, TradeStockDaily prev, String stockCode, String name) {
    if (cur == null
        || prev == null
        || cur.getClosePrice() == null
        || prev.getClosePrice() == null
        || prev.getClosePrice().compareTo(BigDecimal.ZERO) <= 0) {
      return false;
    }
    BigDecimal thr =
        prev.getClosePrice()
            .multiply(
                BigDecimal.ONE.add(
                    limitUpPct(stockCode, name)
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
    return cur.getClosePrice().compareTo(thr) >= 0;
  }

  public BigDecimal limitUpPct(String stockCode, String stockName) {
    if (stockName != null && stockName.toUpperCase().contains("ST")) {
      return BigDecimal.valueOf(4.8);
    }
    String code = SwingCodeUtils.bareCode(stockCode);
    if (code.startsWith("300") || code.startsWith("301") || code.startsWith("688")) {
      return BigDecimal.valueOf(19.8);
    }
    if (code.startsWith("8") || code.startsWith("4")) {
      return BigDecimal.valueOf(29.8);
    }
    return BigDecimal.valueOf(9.8);
  }

  public boolean deathCrossMa10Ma20(List<TradeStockDaily> asc) {
    if (asc == null || asc.size() < 21) {
      return false;
    }
    BigDecimal ma10 = avgClose(asc, 10);
    BigDecimal ma20 = avgClose(asc, 20);
    List<TradeStockDaily> prev = asc.subList(0, asc.size() - 1);
    BigDecimal prevMa10 = avgClose(prev, 10);
    BigDecimal prevMa20 = avgClose(prev, 20);
    if (ma10 == null || ma20 == null || prevMa10 == null || prevMa20 == null) {
      return false;
    }
    return prevMa10.compareTo(prevMa20) >= 0 && ma10.compareTo(ma20) < 0;
  }

  private BigDecimal avgClose(List<TradeStockDaily> asc, int n) {
    if (asc.size() < n) {
      return null;
    }
    List<TradeStockDaily> slice = asc.subList(asc.size() - n, asc.size());
    BigDecimal sum = BigDecimal.ZERO;
    int count = 0;
    for (TradeStockDaily d : slice) {
      if (d.getClosePrice() != null) {
        sum = sum.add(d.getClosePrice());
        count++;
      }
    }
    if (count == 0) {
      return null;
    }
    return sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
  }

  private Long avgVol(List<TradeStockDaily> asc, int n) {
    if (asc.size() < n) {
      return null;
    }
    List<TradeStockDaily> slice = asc.subList(asc.size() - n, asc.size());
    long sum = 0;
    int count = 0;
    for (TradeStockDaily d : slice) {
      if (d.getVolume() != null) {
        sum += d.getVolume();
        count++;
      }
    }
    if (count == 0) {
      return null;
    }
    return sum / count;
  }
}
