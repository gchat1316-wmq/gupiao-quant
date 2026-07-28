package com.quant.service.technical;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.quant.entity.TradeStockDaily;
import com.quant.service.technical.MovingAverageCalculator.MovingAverages;

class MovingAverageCalculatorTest {

  @Test
  void computesMaAndBullishAlignment() {
    List<TradeStockDaily> daily = new ArrayList<>();
    // 构造稳步上涨 60 根
    for (int i = 0; i < 60; i++) {
      TradeStockDaily d = new TradeStockDaily();
      d.setTradeDate(LocalDate.of(2026, 1, 1).plusDays(i));
      BigDecimal close = BigDecimal.valueOf(10 + i * 0.1);
      d.setClosePrice(close);
      d.setHighPrice(close.add(BigDecimal.valueOf(0.05)));
      d.setLowPrice(close.subtract(BigDecimal.valueOf(0.05)));
      d.setOpenPrice(close);
      d.setVolume(1_000_000L + i * 10_000L);
      daily.add(d);
    }
    MovingAverages mas = MovingAverageCalculator.fromDaily(daily);
    assertThat(mas.ma5()).isNotNull();
    assertThat(mas.ma10()).isNotNull();
    assertThat(mas.ma20()).isNotNull();
    assertThat(mas.ma60()).isNotNull();
    assertThat(mas.bullishAlignment()).isTrue();
    assertThat(mas.aboveMa20()).isTrue();
    assertThat(mas.ma20Rising()).isTrue();
    assertThat(mas.volRatio()).isNotNull();
  }

  @Test
  void volumeExpandRatioUsesTwoWindows() {
    List<TradeStockDaily> daily = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      TradeStockDaily d = new TradeStockDaily();
      d.setTradeDate(LocalDate.of(2026, 1, 1).plusDays(i));
      d.setClosePrice(BigDecimal.TEN);
      d.setVolume(i < 20 ? 100L : 200L);
      daily.add(d);
    }
    BigDecimal ratio = MovingAverageCalculator.volumeExpandRatio(daily, 20, 20);
    assertThat(ratio).isEqualByComparingTo("2.0000");
  }
}
