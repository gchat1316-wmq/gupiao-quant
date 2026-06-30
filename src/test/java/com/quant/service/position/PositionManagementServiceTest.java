package com.quant.service.position;

import com.quant.dto.position.PositionAdviceDTO;
import com.quant.dto.position.PositionAdviceRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionManagementServiceTest {

    private final PositionManagementService service = new PositionManagementService();

    private PositionAdviceRequest sampleRequest() {
        PositionAdviceRequest req = new PositionAdviceRequest();
        req.setAccountCapital(new BigDecimal("100000"));
        req.setEntryPrice(new BigDecimal("50.00"));
        req.setStopLossPrice(new BigDecimal("48.00"));
        req.setTargetPrice(new BigDecimal("56.00"));
        req.setRiskPercent(new BigDecimal("0.01"));
        req.setWinRate(new BigDecimal("0.40"));
        return req;
    }

    @Test
    void shouldComputeSizingByTheLessonExample() {
        // Lesson: 1% risk on 100000 account with 2.00 riskPerShare = 1000 max loss,
        // equals 500 shares -> 5 lots after A股 100-share rounding.
        PositionAdviceDTO out = service.advise(sampleRequest());
        assertThat(out.getSizing().getRiskPerShare())
                .isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(out.getSizing().getMaxRiskAmount())
                .isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(out.getSizing().getLotsOf100()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(out.getSizing().getShares()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(out.getSizing().getPositionAmount())
                .isEqualByComparingTo(new BigDecimal("25000.00"));
        assertThat(out.getSizing().getPositionPct())
                .isEqualByComparingTo(new BigDecimal("0.2500"));
    }

    @Test
    void shouldComputeRiskRewardRatioOneToThree() {
        PositionAdviceDTO out = service.advise(sampleRequest());
        assertThat(out.getRiskReward().getRatioLabel()).isEqualTo("1 : 3");
        assertThat(out.getRiskReward().getRatioValue())
                .isEqualByComparingTo(new BigDecimal("3.0000"));
        assertThat(out.getRiskReward().getBreakEvenWinRate())
                .isEqualByComparingTo(new BigDecimal("0.2500"));
        assertThat(out.getRiskReward().getVerdict()).isEqualTo("excellent");
    }

    @Test
    void shouldComputeExpectedValueCorrectly() {
        PositionAdviceDTO out = service.advise(sampleRequest());
        BigDecimal perShare = out.getExpectation().getExpectedValuePerTrade()
                .divide(new BigDecimal("500"), 2, RoundingMode.HALF_UP);
        assertThat(perShare).isEqualByComparingTo(new BigDecimal("1.20"));
        assertThat(out.getExpectation().getExpectedValue100Trades())
                .isEqualByComparingTo(new BigDecimal("60000.00"));
        assertThat(out.getExpectation().getVerdict()).isEqualTo("positive");
    }

    @Test
    void shouldBuildDrawdownTableForTwelveConsecutiveLosses() {
        PositionAdviceDTO out = service.advise(sampleRequest());
        assertThat(out.getDrawdownTable()).hasSize(12);
        PositionAdviceDTO.DrawdownRow row10 = out.getDrawdownTable().get(9);
        assertThat(row10.getConsecutiveLosses()).isEqualTo(10);
        assertThat(row10.getRemainingPct().setScale(4, RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("0.9044"));
        assertThat(row10.getRecoverGainRequired().setScale(4, RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("0.1057"));
    }

    @Test
    void shouldFlagNegativeExpectedValue() {
        PositionAdviceRequest req = sampleRequest();
        req.setTargetPrice(new BigDecimal("52.00"));
        PositionAdviceDTO out = service.advise(req);
        assertThat(out.getExpectation().getExpectedValuePerTrade())
                .isLessThan(BigDecimal.ZERO);
        assertThat(out.getExpectation().getVerdict()).isEqualTo("negative");
        assertThat(out.getVerdict()).contains("负期望");
    }

    @Test
    void shouldFlagPoorRiskReward() {
        PositionAdviceRequest req = sampleRequest();
        req.setTargetPrice(new BigDecimal("51.00"));
        PositionAdviceDTO out = service.advise(req);
        assertThat(out.getRiskReward().getVerdict()).isEqualTo("poor");
        assertThat(out.getVerdict()).contains("风报比低于");
    }

    @Test
    void shouldRejectInvalidPrices() {
        PositionAdviceRequest req = sampleRequest();
        req.setEntryPrice(new BigDecimal("48.00"));
        req.setStopLossPrice(new BigDecimal("50.00"));
        assertThatThrownBy(() -> service.advise(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("入场价必须大于止损价");

        PositionAdviceRequest req2 = sampleRequest();
        req2.setEntryPrice(new BigDecimal("50.00"));
        req2.setTargetPrice(new BigDecimal("49.00"));
        assertThatThrownBy(() -> service.advise(req2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标价必须大于入场价");
    }

    @Test
    void shouldProvideSixLegends() {
        assertThat(service.legends()).hasSize(6);
    }

    @Test
    void shouldProvidePresetRiskRewardTiers() {
        assertThat(service.presets()).hasSize(5);
        assertThat(service.presets().get(2).getRatioValue())
                .isEqualByComparingTo(new BigDecimal("3"));
        assertThat(service.presets().get(2).getBreakEvenWinRate())
                .isEqualByComparingTo(new BigDecimal("0.2500"));
    }

    @Test
    void shouldDefaultRiskPercentAndWinRate() {
        PositionAdviceRequest req = sampleRequest();
        req.setRiskPercent(null);
        req.setWinRate(null);
        PositionAdviceDTO out = service.advise(req);
        assertThat(out.getInput().getRiskPercent())
                .isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(out.getInput().getWinRate())
                .isEqualByComparingTo(new BigDecimal("0.40"));
    }
}
