package com.quant.service.trendwave;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.quant.config.TrendWaveProperties;
import com.quant.entity.MoneyPosition;
import com.quant.entity.MoneySetup;
import com.quant.entity.MoneyStockPool;
import com.quant.entity.MoneyWatch;
import com.quant.entity.TradeStockDaily;
import com.quant.service.technical.MovingAverageCalculator;
import com.quant.service.technical.MovingAverageCalculator.MovingAverages;

class TrendWaveRuleEngineTest {

  private TrendWaveRuleEngine engine;
  private TrendWaveProperties props;

  @BeforeEach
  void setUp() {
    props = new TrendWaveProperties();
    engine = new TrendWaveRuleEngine(props);
  }

  @Test
  void screenDetailRequiresTrendVolumeSector() {
    List<TradeStockDaily> daily = risingSeries(60);
    // 放大近 20 日量能，满足 ≥1.3 倍扩张
    for (int i = 40; i < daily.size(); i++) {
      daily.get(i).setVolume(5_000_000L);
    }
    MovingAverages mas = MovingAverageCalculator.fromDaily(daily);
    Map<String, Object> detail =
        engine.screenDetail(mas, BigDecimal.valueOf(0.5), true, true, BigDecimal.valueOf(30));
    assertThat(detail.get("passed")).isEqualTo(true);
    assertThat(detail.get("bullishAlignment")).isEqualTo(true);
  }

  @Test
  void pullbackBuyTriggersWhenReclaimMa5InZone() {
    List<TradeStockDaily> daily = risingSeries(60);
    MovingAverages mas = MovingAverageCalculator.fromDaily(daily);
    MoneyWatch watch = new MoneyWatch();
    watch.setId(1L);
    watch.setStockCode("600000.SH");
    watch.setStockName("测试");
    watch.setStatus("WATCH_PULLBACK");
    watch.setScreenPassed(1);

    MoneySetup setup = new MoneySetup();
    setup.setId(9L);
    setup.setSetupType("PULLBACK");
    setup.setStatus("ACTIVE");
    setup.setPlatformLow(mas.ma5().subtract(BigDecimal.ONE));
    setup.setPlatformOpen(mas.ma5().add(BigDecimal.ONE));
    setup.setLimitUpVolume(10_000_000L);

    BigDecimal price = mas.ma5().add(BigDecimal.valueOf(0.1));
    TrendWaveContext ctx =
        TrendWaveContext.builder()
            .watch(watch)
            .setups(List.of(setup))
            .pool(pool(false))
            .dailyAsc(daily)
            .mas(mas)
            .latestPrice(price)
            .todayOpen(price.subtract(BigDecimal.valueOf(0.2)))
            .todayLow(setup.getPlatformLow())
            .todayVolume(1_000_000L)
            .eodScan(true)
            .now(LocalDateTime.now())
            .build();

    List<TrendWaveSignal> signals = engine.evaluate(ctx);
    assertThat(signals).isNotEmpty();
    assertThat(signals.get(0).getEventType()).contains("BUY");
    assertThat(signals.get(0).getNextWatchStatus()).isEqualTo("BUY_SIGNAL");
  }

  @Test
  void secondaryStopClosesPosition() {
    MoneyWatch watch = new MoneyWatch();
    watch.setId(1L);
    watch.setStockCode("600000.SH");
    watch.setStockName("测试");
    watch.setStatus("HOLDING");

    MoneyPosition pos = new MoneyPosition();
    pos.setId(2L);
    pos.setEntryPrice(new BigDecimal("100"));
    pos.setPeakPrice(new BigDecimal("100"));
    pos.setStopPrimary(new BigDecimal("95"));
    pos.setStopSecondary(new BigDecimal("92"));
    pos.setPositionPct(BigDecimal.valueOf(100));
    pos.setStatus("HOLDING");
    pos.setProfitTier("T0");

    TrendWaveContext ctx =
        TrendWaveContext.builder()
            .watch(watch)
            .position(pos)
            .pool(pool(false))
            .latestPrice(new BigDecimal("90"))
            .mas(MovingAverageCalculator.fromDaily(risingSeries(60)))
            .eodScan(true)
            .now(LocalDateTime.now())
            .build();

    List<TrendWaveSignal> signals = engine.evaluate(ctx);
    assertThat(signals).extracting(TrendWaveSignal::getEventType).contains("STOP_SECONDARY");
  }

  @Test
  void tier1TakeProfitSellsHalf() {
    MoneyWatch watch = new MoneyWatch();
    watch.setId(1L);
    watch.setStockCode("600000.SH");
    watch.setStockName("测试");
    watch.setStatus("HOLDING");

    MoneyPosition pos = new MoneyPosition();
    pos.setEntryPrice(new BigDecimal("100"));
    pos.setPeakPrice(new BigDecimal("130"));
    pos.setStopSecondary(new BigDecimal("90"));
    pos.setPositionPct(BigDecimal.valueOf(100));
    pos.setStatus("HOLDING");
    pos.setProfitTier("T1");

    // 现价 119：相对成本 +19%(T1)，相对峰值回撤约 8.5% ≥ 8%
    TrendWaveContext ctx =
        TrendWaveContext.builder()
            .watch(watch)
            .position(pos)
            .pool(pool(false))
            .latestPrice(new BigDecimal("119"))
            .mas(MovingAverageCalculator.fromDaily(risingSeries(60)))
            .eodScan(false)
            .now(LocalDateTime.now())
            .build();

    List<TrendWaveSignal> signals = engine.evaluate(ctx);
    assertThat(signals).isNotEmpty();
    assertThat(signals.get(0).getEventType()).startsWith("TP_");
    assertThat(signals.get(0).getNextPositionPct()).isEqualByComparingTo("50");
  }

  private MoneyStockPool pool(boolean paper) {
    MoneyStockPool p = new MoneyStockPool();
    p.setPaperMode(paper ? 1 : 0);
    return p;
  }

  private List<TradeStockDaily> risingSeries(int n) {
    List<TradeStockDaily> daily = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      TradeStockDaily d = new TradeStockDaily();
      d.setTradeDate(LocalDate.of(2026, 1, 1).plusDays(i));
      BigDecimal c = BigDecimal.valueOf(20 + i * 0.15);
      d.setClosePrice(c);
      d.setOpenPrice(c.subtract(BigDecimal.valueOf(0.05)));
      d.setHighPrice(c.add(BigDecimal.valueOf(0.1)));
      d.setLowPrice(c.subtract(BigDecimal.valueOf(0.1)));
      d.setVolume(2_000_000L + i * 20_000L);
      daily.add(d);
    }
    return daily;
  }
}
