package com.quant.techai;

import com.quant.service.techai.TechAiAlertCandidate;
import com.quant.service.techai.TechAiAlertRuleEngine;
import com.quant.service.techai.TechAiAlertThresholds;
import com.quant.service.techai.TechAiMarketContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TechAiAlertRuleEngine")
class TechAiAlertRuleEngineTest {

    private TechAiMarketContext context(BigDecimal price) {
        return TechAiMarketContext.builder()
                .stockCode("300733.sz")
                .stockName("西菱动力")
                .quoteTime(LocalDateTime.of(2026, 5, 28, 10, 0))
                .latestPrice(price)
                .prevClosePrice(new BigDecimal("100"))
                .minute1OpenPrice(new BigDecimal("100"))
                .minute5OpenPrice(new BigDecimal("100"))
                .turnoverRate(new BigDecimal("7.5"))
                .avgTurnoverRate5d(new BigDecimal("3.0"))
                .closePrice3TradingDaysAgo(new BigDecimal("100"))
                .volume(12_000_000L)
                .build();
    }

    @Test
    @DisplayName("triggers minute, daily, turnover and 3-day positive threshold alerts")
    void triggersPositiveThresholds() {
        TechAiAlertRuleEngine engine = new TechAiAlertRuleEngine();

        List<TechAiAlertCandidate> alerts = engine.evaluate(context(new BigDecimal("120")));

        assertThat(alerts).extracting(TechAiAlertCandidate::dedupeKey)
                .contains(
                        "300733.sz|minute_1m_up|3",
                        "300733.sz|minute_5m_up|5",
                        "300733.sz|daily_up|3",
                        "300733.sz|daily_up|5",
                        "300733.sz|daily_up|7",
                        "300733.sz|turnover_rate|150",
                        "300733.sz|turnover_rate|200",
                        "300733.sz|three_day_up|10",
                        "300733.sz|three_day_up|15",
                        "300733.sz|three_day_up|20"
                );
    }

    @Test
    @DisplayName("triggers negative threshold alerts")
    void triggersNegativeThresholds() {
        TechAiAlertRuleEngine engine = new TechAiAlertRuleEngine();

        List<TechAiAlertCandidate> alerts = engine.evaluate(context(new BigDecimal("80")));

        assertThat(alerts).extracting(TechAiAlertCandidate::dedupeKey)
                .contains(
                        "300733.sz|minute_1m_down|-3",
                        "300733.sz|minute_5m_down|-5",
                        "300733.sz|daily_down|-3",
                        "300733.sz|daily_down|-5",
                        "300733.sz|daily_down|-7",
                        "300733.sz|three_day_down|-10",
                        "300733.sz|three_day_down|-15",
                        "300733.sz|three_day_down|-20"
                );
    }

    @Test
    @DisplayName("skips turnover rule when 5-day average turnover is missing")
    void skipsTurnoverWithoutAverage() {
        TechAiMarketContext ctx = TechAiMarketContext.builder()
                .stockCode("300733.sz")
                .stockName("西菱动力")
                .quoteTime(LocalDateTime.of(2026, 5, 28, 10, 0))
                .latestPrice(new BigDecimal("101"))
                .prevClosePrice(new BigDecimal("100"))
                .turnoverRate(new BigDecimal("7.5"))
                .build();

        List<TechAiAlertCandidate> alerts = new TechAiAlertRuleEngine().evaluate(ctx);

        assertThat(alerts).noneMatch(alert -> alert.ruleType().equals("turnover_rate"));
    }

    @Test
    @DisplayName("uses per-stock thresholds when provided")
    void usesCustomThresholds() {
        TechAiAlertThresholds thresholds = TechAiAlertThresholds.builder()
                .minute1Pct(new BigDecimal("6"))
                .minute5Pct(new BigDecimal("8"))
                .dailyPct(new BigDecimal("12"))
                .threeDayPct(new BigDecimal("18"))
                .turnoverRatioPct(new BigDecimal("260"))
                .build();

        List<TechAiAlertCandidate> alerts = new TechAiAlertRuleEngine()
                .evaluate(context(new BigDecimal("120")), thresholds);

        assertThat(alerts).extracting(TechAiAlertCandidate::dedupeKey)
                .contains(
                        "300733.sz|minute_1m_up|6",
                        "300733.sz|minute_5m_up|8",
                        "300733.sz|daily_up|12",
                        "300733.sz|three_day_up|18"
                )
                .doesNotContain(
                        "300733.sz|minute_1m_up|3",
                        "300733.sz|minute_5m_up|5",
                        "300733.sz|daily_up|3",
                        "300733.sz|turnover_rate|150",
                        "300733.sz|turnover_rate|200",
                        "300733.sz|turnover_rate|260",
                        "300733.sz|turnover_rate|300"
                );
    }
}
