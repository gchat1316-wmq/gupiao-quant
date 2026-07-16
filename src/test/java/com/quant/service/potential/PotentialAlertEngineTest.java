package com.quant.service.potential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.config.NotificationProperties;
import com.quant.entity.InvestAlert;
import com.quant.entity.InvestPositionCommon;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.service.notification.NotificationService;
import com.quant.service.techai.TechAiAlertCandidate;
import com.quant.service.techai.TechAiAlertRuleEngine;
import com.quant.service.techai.TechAiPositionEngine;

@ExtendWith(MockitoExtension.class)
@DisplayName("PotentialAlertEngine (dedupe)")
class PotentialAlertEngineTest {

  @Mock private InvestPositionCommonRepository positionRepository;
  @Mock private InvestAlertRepository alertRepository;
  @Mock private TradeStockDailyRepository dailyRepository;
  @Mock private TechAiAlertRuleEngine ruleEngine;
  @Mock private TechAiPositionEngine positionEngine;
  @Mock private NotificationService notificationService;
  @Mock private PotentialQuoteAggregator quoteAggregator;
  @Mock private PotentialPositionCalculator positionCalculator;

  private PotentialAlertEngine engine;

  @BeforeEach
  void setUp() {
    engine =
        new PotentialAlertEngine(
            positionRepository,
            alertRepository,
            dailyRepository,
            ruleEngine,
            positionEngine,
            notificationService,
            quoteAggregator,
            positionCalculator);
  }

  @Nested
  @DisplayName("shouldPush (threshold alert dedupe)")
  class ShouldPush {
    @Test
    void minuteRuleUsesCooldown() {
      TechAiAlertCandidate candidate =
          new TechAiAlertCandidate(
              "002851.SZ",
              "name",
              "minute1",
              "up",
              new BigDecimal("1.5"),
              new BigDecimal("2.0"),
              "title",
              "content", /*minuteRule*/
              true);

      NotificationProperties.QuoteMonitor cfg = new NotificationProperties.QuoteMonitor();
      cfg.setCooldownMinutes(5);
      cfg.setDailyDedupe(true);

      // No recent alert → first push allowed
      when(alertRepository.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(
              eq("002851.SZ"), eq("minute1:1.5")))
          .thenReturn(Optional.empty());
      assertThat(engine.shouldPush(candidate, cfg)).isTrue();

      // Recent alert inside cooldown → blocked
      InvestAlert recent = new InvestAlert();
      recent.setTriggerAt(LocalDateTime.now().minusMinutes(2));
      when(alertRepository.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(
              eq("002851.SZ"), eq("minute1:1.5")))
          .thenReturn(Optional.of(recent));
      assertThat(engine.shouldPush(candidate, cfg)).isFalse();

      // Recent alert outside cooldown (8 min ago, cooldown=5) → allowed
      recent.setTriggerAt(LocalDateTime.now().minusMinutes(8));
      assertThat(engine.shouldPush(candidate, cfg)).isTrue();
    }

    @Test
    void dailyRuleHonorsDailyDedupeFlag() {
      TechAiAlertCandidate candidate =
          new TechAiAlertCandidate(
              "002851.SZ",
              "name",
              "daily",
              "up",
              new BigDecimal("5"),
              new BigDecimal("6"),
              "title",
              "content", /*minuteRule*/
              false);

      NotificationProperties.QuoteMonitor cfg = new NotificationProperties.QuoteMonitor();
      cfg.setCooldownMinutes(5);
      cfg.setDailyDedupe(true);

      // No alert today → allowed
      when(alertRepository.existsByStockCodeAndSignalTypeAndTriggerAtBetween(
              eq("002851.SZ"), eq("daily:5"), any(LocalDateTime.class), any(LocalDateTime.class)))
          .thenReturn(false);
      assertThat(engine.shouldPush(candidate, cfg)).isTrue();

      // Alert already exists today → blocked
      when(alertRepository.existsByStockCodeAndSignalTypeAndTriggerAtBetween(
              eq("002851.SZ"), eq("daily:5"), any(LocalDateTime.class), any(LocalDateTime.class)))
          .thenReturn(true);
      assertThat(engine.shouldPush(candidate, cfg)).isFalse();
    }

    @Test
    void dailyDedupeDisabledAllowsRepeat() {
      TechAiAlertCandidate candidate =
          new TechAiAlertCandidate(
              "002851.SZ",
              "name",
              "daily",
              "up",
              new BigDecimal("5"),
              new BigDecimal("6"),
              "title",
              "content", /*minuteRule*/
              false);

      NotificationProperties.QuoteMonitor cfg = new NotificationProperties.QuoteMonitor();
      cfg.setDailyDedupe(false);

      // existsByStockCode... should not be consulted at all when dailyDedupe=false
      assertThat(engine.shouldPush(candidate, cfg)).isTrue();
      org.mockito.Mockito.verifyNoInteractions(alertRepository);
    }

    @Test
    void minuteRuleWithNullTriggerAtFallsBackToAllow() {
      TechAiAlertCandidate candidate =
          new TechAiAlertCandidate(
              "002851.SZ",
              "name",
              "minute1",
              "up",
              new BigDecimal("1.5"),
              new BigDecimal("2.0"),
              "title",
              "content", /*minuteRule*/
              true);

      NotificationProperties.QuoteMonitor cfg = new NotificationProperties.QuoteMonitor();
      cfg.setCooldownMinutes(5);

      InvestAlert nullTrigger = new InvestAlert();
      nullTrigger.setTriggerAt(null);
      when(alertRepository.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(
              eq("002851.SZ"), eq("minute1:1.5")))
          .thenReturn(Optional.of(nullTrigger));
      assertThat(engine.shouldPush(candidate, cfg)).isTrue();
    }
  }

  @Nested
  @DisplayName("shouldPushPosition (position-signal dedupe)")
  class ShouldPushPosition {
    @Test
    void warnUsesCooldownWindow() {
      NotificationProperties.QuoteMonitor cfg = new NotificationProperties.QuoteMonitor();
      cfg.setCooldownMinutes(10);

      String signalType = "position_add_warn";

      // No prior alert → allowed
      when(alertRepository.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(
              eq("002851.SZ"), eq(signalType)))
          .thenReturn(Optional.empty());
      assertThat(engine.shouldPushPosition("002851.SZ", signalType, false, cfg)).isTrue();

      // Prior alert within cooldown → blocked
      InvestAlert recent = new InvestAlert();
      recent.setTriggerAt(LocalDateTime.now().minusMinutes(3));
      when(alertRepository.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(
              eq("002851.SZ"), eq(signalType)))
          .thenReturn(Optional.of(recent));
      assertThat(engine.shouldPushPosition("002851.SZ", signalType, false, cfg)).isFalse();

      // Prior alert outside cooldown → allowed
      recent.setTriggerAt(LocalDateTime.now().minusMinutes(15));
      assertThat(engine.shouldPushPosition("002851.SZ", signalType, false, cfg)).isTrue();
    }

    @Test
    void confirmUsesDailyExistenceCheck() {
      NotificationProperties.QuoteMonitor cfg = new NotificationProperties.QuoteMonitor();
      cfg.setCooldownMinutes(10);

      String signalType = "position_stop_confirm";

      // No alert today → allowed
      when(alertRepository.existsByStockCodeAndSignalTypeAndTriggerAtBetween(
              eq("002851.SZ"), eq(signalType), any(LocalDateTime.class), any(LocalDateTime.class)))
          .thenReturn(false);
      assertThat(engine.shouldPushPosition("002851.SZ", signalType, true, cfg)).isTrue();

      // Alert already today → blocked
      when(alertRepository.existsByStockCodeAndSignalTypeAndTriggerAtBetween(
              eq("002851.SZ"), eq(signalType), any(LocalDateTime.class), any(LocalDateTime.class)))
          .thenReturn(true);
      assertThat(engine.shouldPushPosition("002851.SZ", signalType, true, cfg)).isFalse();
    }

    @Test
    void confirmWithNoTriggerAtStillChecked() {
      // sanity: a null triggerAt doesn't bypass the daily check
      NotificationProperties.QuoteMonitor cfg = new NotificationProperties.QuoteMonitor();
      cfg.setCooldownMinutes(10);
      when(alertRepository.existsByStockCodeAndSignalTypeAndTriggerAtBetween(
              any(), any(), any(LocalDateTime.class), any(LocalDateTime.class)))
          .thenReturn(true);
      assertThat(engine.shouldPushPosition("X", "position_tp_confirm", true, cfg)).isFalse();
    }
  }

  @Nested
  @DisplayName("positionLevel / thresholds / averageTurnover")
  class Helpers {
    @Test
    void positionLevelMapsSignalsToSeverity() {
      assertThat(engine.positionLevel(TechAiPositionEngine.SIGNAL_STOP)).isEqualTo(3);
      assertThat(engine.positionLevel(TechAiPositionEngine.SIGNAL_ADD)).isEqualTo(2);
      assertThat(engine.positionLevel(TechAiPositionEngine.SIGNAL_TP)).isEqualTo(2);
      assertThat(engine.positionLevel("unknown")).isEqualTo(1);
    }

    @Test
    void thresholdsMirrorsPositionFields() {
      InvestPositionCommon pos = new InvestPositionCommon();
      pos.setAlertMinute1mPct(new BigDecimal("1.5"));
      pos.setAlertMinute5mPct(new BigDecimal("3"));
      pos.setAlertDailyPct(new BigDecimal("5"));
      pos.setAlertThreeDayPct(new BigDecimal("10"));
      pos.setAlertTurnoverRatioPct(new BigDecimal("8"));

      var thresholds = engine.thresholds(pos);
      assertThat(thresholds.getMinute1Pct()).isEqualByComparingTo("1.5");
      assertThat(thresholds.getMinute5Pct()).isEqualByComparingTo("3");
      assertThat(thresholds.getDailyPct()).isEqualByComparingTo("5");
      assertThat(thresholds.getThreeDayPct()).isEqualByComparingTo("10");
      assertThat(thresholds.getTurnoverRatioPct()).isEqualByComparingTo("8");

      // null position → all null
      var allNull = engine.thresholds(null);
      assertThat(allNull.getMinute1Pct()).isNull();
      assertThat(allNull.getTurnoverRatioPct()).isNull();
    }

    @Test
    void averageTurnoverSkipsZeroAndNull() {
      com.quant.entity.TradeStockDaily a = new com.quant.entity.TradeStockDaily();
      a.setTurnoverRate(new BigDecimal("2"));
      com.quant.entity.TradeStockDaily b = new com.quant.entity.TradeStockDaily();
      b.setTurnoverRate(BigDecimal.ZERO);
      com.quant.entity.TradeStockDaily c = new com.quant.entity.TradeStockDaily();
      c.setTurnoverRate(null);
      com.quant.entity.TradeStockDaily d = new com.quant.entity.TradeStockDaily();
      d.setTurnoverRate(new BigDecimal("4"));

      // (2 + 0 + null + 4) filtered → avg of [2, 4] = 3.00 (4 decimal scale, then 4)
      assertThat(engine.averageTurnover(java.util.List.of(a, b, c, d)))
          .isEqualByComparingTo("3.0000");
      // all empty → null
      assertThat(engine.averageTurnover(java.util.List.of(b, c))).isNull();
    }
  }
}
