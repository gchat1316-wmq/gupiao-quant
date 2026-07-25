package com.quant.service.trendwave;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.TrendWaveProperties;
import com.quant.entity.MoneySetup;
import com.quant.entity.MoneyWatch;
import com.quant.entity.TradeStockDaily;
import com.quant.service.technical.LimitUpDetector;
import com.quant.service.technical.LimitUpDetector.LimitUpStreak;
import com.quant.service.technical.MovingAverageCalculator;
import com.quant.service.technical.MovingAverageCalculator.MovingAverages;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrendWaveSetupDetector {

  private final LimitUpDetector limitUpDetector;
  private final TrendWaveRuleEngine ruleEngine;
  private final TrendWaveProperties props;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public MoneySetup detectPullbackSetup(MoneyWatch watch, List<TradeStockDaily> daily) {
    LimitUpStreak streak =
        limitUpDetector.detectLatestStreak(
            watch.getStockCode(),
            watch.getStockName(),
            daily,
            props.getPullback().getMinLimitUp(),
            props.getPullback().getMaxLimitUp(),
            props.getPullback().getLookbackDays());
    if (streak == null) {
      return null;
    }
    MoneySetup setup = new MoneySetup();
    setup.setWatchId(watch.getId());
    setup.setSetupType("PULLBACK");
    setup.setStatus("ACTIVE");
    setup.setLimitUpCount(streak.streakDays());
    setup.setPlatformOpen(streak.firstOpen());
    setup.setPlatformLow(streak.firstLow() != null ? streak.firstLow() : streak.firstOpen());
    setup.setLimitUpVolume(streak.firstVolume());
    try {
      setup.setLimitUpDates(
          objectMapper.writeValueAsString(
              streak.bars().stream().map(b -> b.tradeDate().toString()).collect(Collectors.toList())));
    } catch (Exception e) {
      setup.setLimitUpDates("[]");
    }
    return setup;
  }

  public MoneySetup detectBreakoutSetup(MoneyWatch watch, List<TradeStockDaily> daily) {
    List<TradeStockDaily> asc = MovingAverageCalculator.sortedAsc(daily);
    MovingAverages mas = MovingAverageCalculator.fromDaily(asc);
    if (mas == null || !mas.bullishAlignment() || !mas.aboveMa20() || !mas.ma20Rising()) {
      return null;
    }
    int min = props.getBreakout().getMinPlatformDays();
    int max = props.getBreakout().getMaxPlatformDays();
    if (asc.size() < max + 5) {
      return null;
    }
    // 取最近 max 日作为候选横盘窗口，要求不破 ma20，且振幅收窄
    List<TradeStockDaily> window = asc.subList(asc.size() - max, asc.size());
    boolean aboveMa20 =
        window.stream()
            .allMatch(
                d ->
                    d.getClosePrice() != null
                        && mas.ma20() != null
                        && d.getClosePrice().compareTo(mas.ma20()) >= 0);
    if (!aboveMa20) {
      return null;
    }
    if (!ruleEngine.isPlatformTighten(window, props.getBreakout().getRangeTightenPct())) {
      // 尝试更短窗口
      boolean found = false;
      for (int days = max; days >= min; days--) {
        List<TradeStockDaily> w = asc.subList(asc.size() - days, asc.size());
        if (ruleEngine.isPlatformTighten(w, props.getBreakout().getRangeTightenPct())) {
          window = w;
          found = true;
          break;
        }
      }
      if (!found) {
        return null;
      }
    }
    BigDecimal high = ruleEngine.platformHigh(window);
    if (high == null) {
      return null;
    }
    MoneySetup setup = new MoneySetup();
    setup.setWatchId(watch.getId());
    setup.setSetupType("BREAKOUT");
    setup.setStatus("ACTIVE");
    setup.setPlatformHigh(high);
    setup.setPlatformDays(window.size());
    return setup;
  }
}
