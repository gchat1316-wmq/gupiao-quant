package com.quant.service.techai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.quant.entity.TradeStockDaily;

@Component
public class TechAiAtrCalculator {

  /**
   * 计算 ATR(period)。dailyRecords 可为任意顺序（内部按日期升序处理）， TR = max(high-low, |high-prevClose|,
   * |low-prevClose|)，取最近 period 个 TR 的均值。 数据不足时返回 null。
   */
  public BigDecimal atr(List<TradeStockDaily> dailyRecords, int period) {
    if (dailyRecords == null || dailyRecords.size() < 2 || period <= 0) {
      return null;
    }
    List<TradeStockDaily> sorted = new ArrayList<>(dailyRecords);
    sorted.sort(Comparator.comparing(TradeStockDaily::getTradeDate));

    List<BigDecimal> trueRanges = new ArrayList<>();
    for (int i = 1; i < sorted.size(); i++) {
      TradeStockDaily cur = sorted.get(i);
      TradeStockDaily prev = sorted.get(i - 1);
      if (cur.getHighPrice() == null || cur.getLowPrice() == null || prev.getClosePrice() == null) {
        continue;
      }
      BigDecimal hl = cur.getHighPrice().subtract(cur.getLowPrice()).abs();
      BigDecimal hc = cur.getHighPrice().subtract(prev.getClosePrice()).abs();
      BigDecimal lc = cur.getLowPrice().subtract(prev.getClosePrice()).abs();
      trueRanges.add(hl.max(hc).max(lc));
    }
    if (trueRanges.isEmpty()) {
      return null;
    }
    int take = Math.min(period, trueRanges.size());
    List<BigDecimal> window = trueRanges.subList(trueRanges.size() - take, trueRanges.size());
    BigDecimal sum = window.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return sum.divide(BigDecimal.valueOf(take), 4, RoundingMode.HALF_UP);
  }
}
