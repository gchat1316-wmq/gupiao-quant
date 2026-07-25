package com.quant.service.technical;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.quant.entity.TradeStockDaily;
import com.quant.service.technical.LimitUpDetector.LimitUpStreak;

class LimitUpDetectorTest {

  private final LimitUpDetector detector = new LimitUpDetector();

  @Test
  void detectsMainBoardLimitUp() {
    TradeStockDaily prev = bar(LocalDate.of(2026, 7, 1), "10.00");
    TradeStockDaily cur = bar(LocalDate.of(2026, 7, 2), "11.00");
    assertThat(detector.isLimitUp(cur, prev, "600000.SH", "浦发银行")).isTrue();
  }

  @Test
  void detectsChiNextLimitUpNear20Pct() {
    TradeStockDaily prev = bar(LocalDate.of(2026, 7, 1), "10.00");
    TradeStockDaily cur = bar(LocalDate.of(2026, 7, 2), "12.00");
    assertThat(detector.isLimitUp(cur, prev, "300750.SZ", "宁德时代")).isTrue();
  }

  @Test
  void detectLatestStreakWithinLookback() {
    List<TradeStockDaily> daily = new ArrayList<>();
    daily.add(bar(LocalDate.of(2026, 7, 1), "10.00"));
    TradeStockDaily lu = bar(LocalDate.of(2026, 7, 2), "11.00");
    lu.setOpenPrice(new BigDecimal("10.20"));
    lu.setLowPrice(new BigDecimal("10.10"));
    lu.setVolume(5_000_000L);
    daily.add(lu);
    daily.add(bar(LocalDate.of(2026, 7, 3), "10.80"));

    LimitUpStreak streak =
        detector.detectLatestStreak("600000.SH", "测试", daily, 1, 2, 20);
    assertThat(streak).isNotNull();
    assertThat(streak.streakDays()).isEqualTo(1);
    assertThat(streak.firstOpen()).isEqualByComparingTo("10.20");
    assertThat(streak.firstLow()).isEqualByComparingTo("10.10");
  }

  private TradeStockDaily bar(LocalDate date, String close) {
    TradeStockDaily d = new TradeStockDaily();
    d.setTradeDate(date);
    d.setClosePrice(new BigDecimal(close));
    d.setOpenPrice(new BigDecimal(close));
    d.setHighPrice(new BigDecimal(close));
    d.setLowPrice(new BigDecimal(close));
    d.setVolume(1_000_000L);
    return d;
  }
}
