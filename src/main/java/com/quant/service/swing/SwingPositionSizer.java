package com.quant.service.swing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.quant.config.SwingTradingProperties;
import com.quant.entity.SwingWatchlist;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SwingPositionSizer {

  private final SwingTradingProperties props;

  /** 按账户权益与单票上限计算股数，向下取整到 100 股。 */
  public int calcShares(SwingWatchlist watch, BigDecimal price) {
    if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
      return 0;
    }
    BigDecimal equity =
        watch.getAccountEquity() != null
            ? watch.getAccountEquity()
            : props.getDefaultAccountEquity();
    BigDecimal pct =
        watch.getMaxPositionPct() != null
            ? watch.getMaxPositionPct()
            : props.getMaxSinglePositionPct();
    BigDecimal budget =
        equity.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    int raw = budget.divide(price, 0, RoundingMode.DOWN).intValue();
    int lots = (raw / 100) * 100;
    return Math.max(lots, 0);
  }

  public int halfLots(int shares) {
    if (shares <= 0) {
      return 0;
    }
    int half = shares / 2;
    half = (half / 100) * 100;
    if (half <= 0) {
      return shares; // 不足一手则全卖
    }
    return half;
  }
}
