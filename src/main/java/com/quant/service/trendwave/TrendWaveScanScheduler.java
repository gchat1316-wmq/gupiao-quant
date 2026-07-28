package com.quant.service.trendwave;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.quant.config.TrendWaveProperties;
import com.quant.dto.trendwave.MoneyScanResultDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trend-wave", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TrendWaveScanScheduler {

  private final TrendWaveService trendWaveService;
  private final TrendWaveProperties properties;

  @Scheduled(cron = "${trend-wave.intraday-cron:0 */1 9-15 * * MON-FRI}")
  public void intradayScan() {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      MoneyScanResultDTO result = trendWaveService.scan(false);
      if (result.getSignals() > 0) {
        log.info("trend-wave intraday: {}", result.getMessage());
      }
    } catch (Exception e) {
      log.warn("trend-wave intraday scan failed: {}", e.getMessage());
    }
  }

  @Scheduled(cron = "${trend-wave.eod-cron:0 10 15 * * MON-FRI}")
  public void eodScan() {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      MoneyScanResultDTO result = trendWaveService.scan(true);
      log.info("trend-wave eod: {}", result.getMessage());
    } catch (Exception e) {
      log.warn("trend-wave eod scan failed: {}", e.getMessage());
    }
  }
}
