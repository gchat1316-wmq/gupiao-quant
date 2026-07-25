package com.quant.service.swing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.quant.config.SwingTradingProperties;
import com.quant.entity.SwingWatchlist;

class SwingPositionSizerTest {

  @Test
  void roundsDownToLots() {
    SwingTradingProperties props = new SwingTradingProperties();
    SwingPositionSizer sizer = new SwingPositionSizer(props);
    SwingWatchlist w = new SwingWatchlist();
    w.setAccountEquity(new BigDecimal("100000"));
    w.setMaxPositionPct(new BigDecimal("15"));
    // 15000 / 10.5 ≈ 1428 → 1400
    assertThat(sizer.calcShares(w, new BigDecimal("10.50"))).isEqualTo(1400);
  }

  @Test
  void halfLotsFallsBackToAllWhenTiny() {
    SwingTradingProperties props = new SwingTradingProperties();
    SwingPositionSizer sizer = new SwingPositionSizer(props);
    assertThat(sizer.halfLots(100)).isEqualTo(100);
    assertThat(sizer.halfLots(300)).isEqualTo(100);
  }
}
