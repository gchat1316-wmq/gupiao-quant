package com.quant.service.technical;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.quant.entity.TradeStockDaily;

/** 涨停识别与连续涨停 streak 检测（与大阳线战法阈值逻辑一致）。 */
@Component
public class LimitUpDetector {

  public record LimitUpBar(
      LocalDate tradeDate,
      BigDecimal openPrice,
      BigDecimal lowPrice,
      BigDecimal closePrice,
      Long volume) {}

  public record LimitUpStreak(
      int streakDays,
      LocalDate firstLimitUpDate,
      LocalDate lastLimitUpDate,
      BigDecimal firstOpen,
      BigDecimal firstLow,
      BigDecimal firstClose,
      Long firstVolume,
      BigDecimal lastClose,
      List<LimitUpBar> bars) {}

  public boolean isLimitUp(
      TradeStockDaily current, TradeStockDaily prev, String stockCode, String stockName) {
    if (current == null
        || prev == null
        || current.getClosePrice() == null
        || prev.getClosePrice() == null
        || prev.getClosePrice().compareTo(BigDecimal.ZERO) <= 0) {
      return false;
    }
    BigDecimal threshold =
        prev.getClosePrice()
            .multiply(
                BigDecimal.ONE.add(
                    limitUpPct(stockCode, stockName)
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
    BigDecimal pct =
        current
            .getClosePrice()
            .subtract(prev.getClosePrice())
            .divide(prev.getClosePrice(), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    return current.getClosePrice().compareTo(threshold) >= 0
        || pct.compareTo(limitUpPct(stockCode, stockName)) >= 0;
  }

  public BigDecimal limitUpPct(String stockCode, String stockName) {
    if (stockName != null && stockName.toUpperCase().contains("ST")) {
      return BigDecimal.valueOf(4.8);
    }
    String code = stockCode == null ? "" : stockCode.toUpperCase();
    String bare = code.contains(".") ? code.substring(0, code.indexOf('.')) : code;
    if (bare.startsWith("300") || bare.startsWith("301") || bare.startsWith("688")) {
      return BigDecimal.valueOf(19.8);
    }
    if (bare.startsWith("8") || bare.startsWith("4")) {
      return BigDecimal.valueOf(29.8);
    }
    return BigDecimal.valueOf(9.8);
  }

  /**
   * 在最近 K 线中找最新一段满足 [minStreak, maxStreak] 的涨停 streak，且最后涨停日距最新日不超过 lookbackDays。
   */
  public LimitUpStreak detectLatestStreak(
      String stockCode,
      String stockName,
      List<TradeStockDaily> recent,
      int minStreak,
      int maxStreak,
      int lookbackDays) {
    if (recent == null || recent.size() < 2) {
      return null;
    }
    List<TradeStockDaily> asc = new ArrayList<>(recent);
    asc.sort(Comparator.comparing(TradeStockDaily::getTradeDate));
    LocalDate latestDate = asc.get(asc.size() - 1).getTradeDate();
    for (int end = asc.size() - 1; end >= 1; ) {
      TradeStockDaily current = asc.get(end);
      TradeStockDaily prev = asc.get(end - 1);
      if (!isLimitUp(current, prev, stockCode, stockName)) {
        end--;
        continue;
      }
      int start = end;
      while (start - 1 >= 1
          && isLimitUp(asc.get(start - 1), asc.get(start - 2), stockCode, stockName)) {
        start--;
      }
      int streakDays = end - start + 1;
      long daysBetween =
          java.time.temporal.ChronoUnit.DAYS.between(asc.get(end).getTradeDate(), latestDate);
      if (streakDays >= minStreak && streakDays <= maxStreak && daysBetween <= lookbackDays) {
        List<LimitUpBar> bars = new ArrayList<>();
        for (int i = start; i <= end; i++) {
          TradeStockDaily d = asc.get(i);
          bars.add(
              new LimitUpBar(
                  d.getTradeDate(),
                  scale(d.getOpenPrice()),
                  scale(d.getLowPrice()),
                  scale(d.getClosePrice()),
                  d.getVolume()));
        }
        TradeStockDaily first = asc.get(start);
        TradeStockDaily last = asc.get(end);
        return new LimitUpStreak(
            streakDays,
            first.getTradeDate(),
            last.getTradeDate(),
            scale(first.getOpenPrice()),
            scale(first.getLowPrice()),
            scale(first.getClosePrice()),
            first.getVolume(),
            scale(last.getClosePrice()),
            bars);
      }
      end = start - 1;
    }
    return null;
  }

  private BigDecimal scale(BigDecimal v) {
    return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
  }
}
