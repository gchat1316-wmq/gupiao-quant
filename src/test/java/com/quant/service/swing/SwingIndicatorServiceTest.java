package com.quant.service.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.entity.TradeStockDaily;
import com.quant.repository.TradeStockDailyRepository;

@ExtendWith(MockitoExtension.class)
class SwingIndicatorServiceTest {

  @Mock private TradeStockDailyRepository dailyRepository;
  private SwingIndicatorService service;

  @BeforeEach
  void setUp() {
    service = new SwingIndicatorService(dailyRepository);
  }

  @Test
  void computeBullishAlignment() {
    List<TradeStockDaily> asc = risingSeries(70, new BigDecimal("10"));
    when(dailyRepository.findTop80ByStockCodeOrderByTradeDateDesc(anyString()))
        .thenReturn(descOf(asc));

    var loaded = service.loadAsc("600519.SH", 80);
    var snap = service.compute(loaded);
    assertThat(snap).isNotNull();
    assertThat(snap.bullishAligned()).isTrue();
    assertThat(snap.aboveMa20()).isTrue();
    assertThat(snap.ma20Rising()).isTrue();
    assertThat(snap.volRatio()).isNotNull();
  }

  @Test
  void detectLimitUpMainBoard() {
    TradeStockDaily prev = bar(LocalDate.of(2026, 7, 1), "10.00", "10.00", 1_000_000L);
    TradeStockDaily cur = bar(LocalDate.of(2026, 7, 2), "10.50", "11.00", 2_000_000L);
    assertThat(service.isLimitUp(cur, prev, "600519.SH", "贵州茅台")).isTrue();
  }

  @Test
  void deathCrossDetectedAfterSharpReversal() {
    // 40 日缓涨，随后 15 日急跌，使 MA10 自上向下穿越 MA20
    List<TradeStockDaily> asc = new ArrayList<>();
    LocalDate d = LocalDate.of(2026, 1, 1);
    for (int i = 0; i < 40; i++) {
      BigDecimal close = BigDecimal.valueOf(10 + i * 0.5);
      asc.add(bar(d.plusDays(i), close.toPlainString(), close.toPlainString(), 1_000_000L));
    }
    for (int i = 0; i < 15; i++) {
      BigDecimal close = BigDecimal.valueOf(30 - i * 1.5);
      asc.add(bar(d.plusDays(40 + i), close.toPlainString(), close.toPlainString(), 1_000_000L));
    }
    boolean crossed = false;
    for (int end = 25; end <= asc.size(); end++) {
      if (service.deathCrossMa10Ma20(asc.subList(0, end))) {
        crossed = true;
        break;
      }
    }
    assertThat(crossed).isTrue();
  }

  @Test
  void limitUpPctForChiNext() {
    assertThat(service.limitUpPct("300750.SZ", "宁德时代"))
        .isEqualByComparingTo(BigDecimal.valueOf(19.8));
  }

  private static List<TradeStockDaily> risingSeries(int n, BigDecimal start) {
    List<TradeStockDaily> list = new ArrayList<>();
    LocalDate d = LocalDate.of(2026, 1, 1);
    for (int i = 0; i < n; i++) {
      BigDecimal c = start.add(BigDecimal.valueOf(i).multiply(BigDecimal.valueOf(0.2)));
      list.add(bar(d.plusDays(i), c.toPlainString(), c.toPlainString(), 1_000_000L + i * 10_000L));
    }
    return list;
  }

  private static List<TradeStockDaily> descOf(List<TradeStockDaily> asc) {
    List<TradeStockDaily> desc = new ArrayList<>(asc);
    java.util.Collections.reverse(desc);
    return desc;
  }

  private static TradeStockDaily bar(LocalDate date, String open, String close, long vol) {
    TradeStockDaily d = new TradeStockDaily();
    d.setTradeDate(date);
    d.setOpenPrice(new BigDecimal(open));
    d.setHighPrice(new BigDecimal(close));
    d.setLowPrice(new BigDecimal(open));
    d.setClosePrice(new BigDecimal(close));
    d.setVolume(vol);
    return d;
  }
}
