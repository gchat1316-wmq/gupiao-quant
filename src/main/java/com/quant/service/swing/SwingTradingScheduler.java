package com.quant.service.swing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.quant.config.SwingTradingProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SwingTradingScheduler {

  private final SwingTradingProperties props;
  private final SwingScanService scanService;

  @Scheduled(cron = "${swing-trading.intraday-cron:0 */2 9-15 * * MON-FRI}")
  public void intraday() {
    if (!props.isEnabled()) {
      return;
    }
    var result = scanService.scanAll(false);
    if (result.scanned() > 0) {
      log.info(
          "swing intraday scan: scanned={}, setups={}, signals={}, fills={}",
          result.scanned(),
          result.setups(),
          result.signals(),
          result.fills());
    }
  }

  @Scheduled(cron = "${swing-trading.eod-cron:0 5 15 * * MON-FRI}")
  public void eod() {
    if (!props.isEnabled()) {
      return;
    }
    var result = scanService.scanEod();
    log.info(
        "swing eod scan: scanned={}, setups={}, signals={}, fills={}",
        result.scanned(),
        result.setups(),
        result.signals(),
        result.fills());
  }
}
