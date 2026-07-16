package com.quant.service.potential;

import com.quant.entity.InvestPositionCommon;
import com.quant.entity.PotentialPool;
import com.quant.entity.PotentialPositionFill;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.PotentialPoolRepository;
import com.quant.repository.PotentialPositionFillRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.service.techai.TechAiAtrCalculator;
import com.quant.service.techai.TechAiPositionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PotentialPositionCalculator")
class PotentialPositionCalculatorTest {

  @Mock private PotentialPoolRepository poolRepository;
  @Mock private PotentialPositionFillRepository fillRepository;
  @Mock private InvestPositionCommonRepository positionRepository;
  @Mock private TradeStockDailyRepository dailyRepository;
  @Mock private TechAiPositionEngine positionEngine;
  @Mock private TechAiAtrCalculator atrCalculator;

  private PotentialPositionCalculator calculator;

  @BeforeEach
  void setUp() {
    calculator = new PotentialPositionCalculator(
        poolRepository, fillRepository, positionRepository,
        dailyRepository, positionEngine, atrCalculator);
  }

  @Nested
  @DisplayName("newDefaultPosition (static factory)")
  class NewDefaultPosition {
    @Test
    void usesWatchingWhenStatusNullOrBlank() {
      InvestPositionCommon pos = PotentialPositionCalculator.newDefaultPosition("002851.SZ", null);
      assertThat(pos.getStockCode()).isEqualTo("002851.SZ");
      assertThat(pos.getPoolType()).isEqualTo("potential");
      assertThat(pos.getStatus()).isEqualTo("watching");
      assertThat(pos.getAlertState()).isEqualTo("none");
      assertThat(pos.getPositionState()).isEqualTo("none");
      assertThat(pos.getPositionLots()).isEqualByComparingTo("0");
      assertThat(pos.getRealizedPnl()).isEqualByComparingTo("0");
      assertThat(pos.getAddCount()).isEqualTo(0);
      assertThat(pos.getTakeProfitDone()).isEqualTo(0);
      assertThat(pos.getAddStepPct()).isEqualByComparingTo("10");
      assertThat(pos.getTrailPct()).isEqualByComparingTo("10");
      assertThat(pos.getAddSizeSchedule()).isEqualTo("1,1,1");
      assertThat(pos.getTakeProfitPct()).isEqualByComparingTo("50");
      assertThat(pos.getBreakevenAfterTp()).isEqualTo(1);
      assertThat(pos.getUseAtr()).isEqualTo(0);
      assertThat(pos.getAtrPeriod()).isEqualTo(14);
      assertThat(pos.getAtrAddMult()).isEqualByComparingTo("1");
      assertThat(pos.getAtrTrailMult()).isEqualByComparingTo("2");

      // blank request status → also "watching"
      assertThat(PotentialPositionCalculator.newDefaultPosition("X", "  ").getStatus())
          .isEqualTo("watching");
    }

    @Test
    void preservesRequestStatusWhenNonBlank() {
      assertThat(PotentialPositionCalculator.newDefaultPosition("X", "holding").getStatus())
          .isEqualTo("holding");
      assertThat(PotentialPositionCalculator.newDefaultPosition("X", "exited").getStatus())
          .isEqualTo("exited");
    }
  }

  @Nested
  @DisplayName("getOrCreatePosition")
  class GetOrCreatePosition {
    @Test
    void returnsExistingWhenFound() {
      InvestPositionCommon existing = new InvestPositionCommon();
      existing.setStockCode("002851.SZ");
      existing.setStatus("holding");
      when(positionRepository.findByStockCodeAndPoolType("002851.SZ", "potential"))
          .thenReturn(Optional.of(existing));

      InvestPositionCommon result = calculator.getOrCreatePosition("002851.SZ");

      assertThat(result).isSameAs(existing);
      verify(positionRepository, never()).save(any());
    }

    @Test
    void createsAndSavesDefaultWhenMissing() {
      when(positionRepository.findByStockCodeAndPoolType("NEW.SH", "potential"))
          .thenReturn(Optional.empty());
      when(positionRepository.save(any(InvestPositionCommon.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      InvestPositionCommon result = calculator.getOrCreatePosition("NEW.SH");

      assertThat(result.getStockCode()).isEqualTo("NEW.SH");
      assertThat(result.getStatus()).isEqualTo("watching");
      assertThat(result.getPoolType()).isEqualTo("potential");
      verify(positionRepository).save(any(InvestPositionCommon.class));
    }
  }

  @Nested
  @DisplayName("defaultTargetPrice / effectiveTargetPrice")
  class TargetPrice {
    @Test
    void defaultTargetPriceFromEntryAndPct() {
      BigDecimal target = calculator.defaultTargetPrice(new BigDecimal("10"), new BigDecimal("50"));
      assertThat(target).isEqualByComparingTo("15.00");

      BigDecimal negative = calculator.defaultTargetPrice(new BigDecimal("10"), new BigDecimal("-10"));
      assertThat(negative).isNull();

      BigDecimal zeroEntry = calculator.defaultTargetPrice(BigDecimal.ZERO, new BigDecimal("50"));
      assertThat(zeroEntry).isNull();

      assertThat(calculator.defaultTargetPrice(null, new BigDecimal("50"))).isNull();
      assertThat(calculator.defaultTargetPrice(new BigDecimal("10"), null)).isNull();
    }

    @Test
    void effectiveTargetPriceReturnsExplicitOverride() {
      InvestPositionCommon pos = new InvestPositionCommon();
      pos.setEntryPrice(new BigDecimal("10"));
      pos.setTakeProfitPct(new BigDecimal("50"));
      pos.setTargetSellPrice(new BigDecimal("99.99"));
      assertThat(calculator.effectiveTargetPrice(pos)).isEqualByComparingTo("99.99");
    }

    @Test
    void effectiveTargetPriceDerivesFromEntryWhenUnset() {
      InvestPositionCommon pos = new InvestPositionCommon();
      pos.setEntryPrice(new BigDecimal("20"));
      pos.setTakeProfitPct(new BigDecimal("25"));
      assertThat(calculator.effectiveTargetPrice(pos)).isEqualByComparingTo("25.00");
    }

    @Test
    void effectiveTargetPriceNullForNullPosition() {
      assertThat(calculator.effectiveTargetPrice(null)).isNull();
    }
  }

  @Nested
  @DisplayName("isAtrMode")
  class IsAtrMode {
    @Test
    void onlyTrueWhenUseAtrEqualsOne() {
      assertThat(calculator.isAtrMode(null)).isFalse();

      InvestPositionCommon off = new InvestPositionCommon();
      off.setUseAtr(0);
      assertThat(calculator.isAtrMode(off)).isFalse();

      InvestPositionCommon nullFlag = new InvestPositionCommon();
      assertThat(calculator.isAtrMode(nullFlag)).isFalse();

      InvestPositionCommon on = new InvestPositionCommon();
      on.setUseAtr(1);
      assertThat(calculator.isAtrMode(on)).isTrue();
    }
  }

  @Nested
  @DisplayName("recomputeAggregates")
  class RecomputeAggregates {
    @Test
    void noFillsClearsAllFieldsAndSetsPositionStateNone() {
      InvestPositionCommon pos = new InvestPositionCommon();
      pos.setStockCode("002851.SZ");
      pos.setAvgCost(new BigDecimal("99"));
      pos.setEntryPrice(new BigDecimal("99"));
      pos.setTotalInvested(new BigDecimal("999"));

      PotentialPool pool = new PotentialPool();
      pool.setId(7);
      pool.setStockCode("002851.SZ");
      when(poolRepository.findByStockCode("002851.SZ")).thenReturn(Optional.of(pool));
      when(fillRepository.findByPoolIdOrderByFilledAtAscIdAsc(7)).thenReturn(List.of());

      calculator.recomputeAggregates(pos);

      assertThat(pos.getPositionLots()).isEqualByComparingTo("0");
      assertThat(pos.getAvgCost()).isNull();
      assertThat(pos.getEntryPrice()).isNull();
      assertThat(pos.getLastAddPrice()).isNull();
      assertThat(pos.getPeakPrice()).isNull();
      assertThat(pos.getStopPrice()).isNull();
      assertThat(pos.getTotalInvested()).isEqualByComparingTo("0");
      assertThat(pos.getOpenedAt()).isNull();
      assertThat(pos.getTakeProfitDone()).isEqualTo(0);
      assertThat(pos.getPositionState()).isEqualTo("none");
      verify(positionEngine, never()).evaluate(any(TechAiPositionEngine.PoolView.class), any(), any());
    }

    @Test
    void openFillSetsHoldingStateAndAverages() {
      PotentialPool pool = poolWithId(7, "002851.SZ");
      when(poolRepository.findByStockCode("002851.SZ")).thenReturn(Optional.of(pool));

      PotentialPositionFill open = fill(1L, "open", "10", "2", LocalDateTime.of(2026, 7, 16, 9, 30));
      when(fillRepository.findByPoolIdOrderByFilledAtAscIdAsc(7)).thenReturn(List.of(open));

      // Position engine returns a non-null stop price to confirm evaluate() is called
      TechAiPositionEngine.PositionPlan plan =
          TechAiPositionEngine.PositionPlan.builder().stopPrice(new BigDecimal("9")).build();
      when(positionEngine.evaluate(any(TechAiPositionEngine.PoolView.class), any(), any()))
          .thenReturn(plan);

      InvestPositionCommon pos = new InvestPositionCommon();
      pos.setStockCode("002851.SZ");
      pos.setTakeProfitPct(new BigDecimal("50"));

      calculator.recomputeAggregates(pos);

      assertThat(pos.getPositionLots()).isEqualByComparingTo("2");
      assertThat(pos.getAvgCost()).isEqualByComparingTo("10.00");
      assertThat(pos.getEntryPrice()).isEqualByComparingTo("10");
      assertThat(pos.getLastAddPrice()).isEqualByComparingTo("10");
      assertThat(pos.getPeakPrice()).isEqualByComparingTo("10");
      assertThat(pos.getOpenedAt()).isEqualTo(LocalDateTime.of(2026, 7, 16, 9, 30));
      assertThat(pos.getPositionState()).isEqualTo("holding");
      assertThat(pos.getStatus()).isEqualTo("holding");
      // 50% target → entry 10 × 1.5 = 15
      assertThat(pos.getTargetSellPrice()).isEqualByComparingTo("15.00");
      // totalInvested = avg * lots * 100 shares/lot = 10 * 2 * 100 = 2000
      assertThat(pos.getTotalInvested()).isEqualByComparingTo("2000.00");
      assertThat(pos.getStopPrice()).isEqualByComparingTo("9");
    }

    @Test
    void addFillUpdatesAverageCostAndIncrementsAddCount() {
      PotentialPool pool = poolWithId(7, "002851.SZ");
      when(poolRepository.findByStockCode("002851.SZ")).thenReturn(Optional.of(pool));

      PotentialPositionFill open = fill(1L, "open", "10", "2", LocalDateTime.of(2026, 7, 16, 9, 30));
      PotentialPositionFill add = fill(2L, "add", "12", "2", LocalDateTime.of(2026, 7, 16, 11, 0));
      when(fillRepository.findByPoolIdOrderByFilledAtAscIdAsc(7)).thenReturn(List.of(open, add));

      TechAiPositionEngine.PositionPlan plan =
          TechAiPositionEngine.PositionPlan.builder().stopPrice(new BigDecimal("10")).build();
      when(positionEngine.evaluate(any(TechAiPositionEngine.PoolView.class), any(), any()))
          .thenReturn(plan);

      InvestPositionCommon pos = new InvestPositionCommon();
      pos.setStockCode("002851.SZ");
      pos.setTakeProfitPct(new BigDecimal("50"));

      calculator.recomputeAggregates(pos);

      // (10 * 2 + 12 * 2) / 4 = 11 → avgCost = 11.00
      assertThat(pos.getAvgCost()).isEqualByComparingTo("11.00");
      assertThat(pos.getPositionLots()).isEqualByComparingTo("4");
      assertThat(pos.getLastAddPrice()).isEqualByComparingTo("12");
      assertThat(pos.getPeakPrice()).isEqualByComparingTo("12"); // peak of [10,12]
      assertThat(pos.getAddCount()).isEqualTo(1); // one add
      // Pure buy path (open + add) → no sell → "holding" (scaled flag only set by sells)
      assertThat(pos.getPositionState()).isEqualTo("holding");
      assertThat(pos.getStatus()).isEqualTo("holding");
    }

    @Test
    void clearFillSetsExitedStateAndZerosLots() {
      PotentialPool pool = poolWithId(7, "002851.SZ");
      when(poolRepository.findByStockCode("002851.SZ")).thenReturn(Optional.of(pool));

      PotentialPositionFill open = fill(1L, "open", "10", "2", LocalDateTime.of(2026, 7, 16, 9, 30));
      PotentialPositionFill clear = fill(2L, "clear", "15", null, LocalDateTime.of(2026, 7, 16, 14, 30));
      when(fillRepository.findByPoolIdOrderByFilledAtAscIdAsc(7)).thenReturn(List.of(open, clear));

      // No positionEngine call expected (exited path returns early)
      InvestPositionCommon pos = new InvestPositionCommon();
      pos.setStockCode("002851.SZ");
      pos.setTakeProfitPct(new BigDecimal("50"));

      calculator.recomputeAggregates(pos);

      assertThat(pos.getPositionLots()).isEqualByComparingTo("0");
      assertThat(pos.getPositionState()).isEqualTo("exited");
      assertThat(pos.getStatus()).isEqualTo("exited");
      assertThat(pos.getAvgCost()).isNull();
      assertThat(pos.getEntryPrice()).isNull();
      assertThat(pos.getOpenedAt()).isEqualTo(LocalDateTime.of(2026, 7, 16, 9, 30));
      // Realized = (15 - 10) * 2 lots * 100 shares = 1000
      assertThat(pos.getRealizedPnl()).isEqualByComparingTo("1000.00");
      // exited branch resets takeProfitDone to 0 (a fresh position would re-evaluate)
      assertThat(pos.getTakeProfitDone()).isEqualTo(0);
      verify(positionEngine, never()).evaluate(any(TechAiPositionEngine.PoolView.class), any(), any());
    }

    @Test
    void throwsWhenPoolMissing() {
      InvestPositionCommon pos = new InvestPositionCommon();
      pos.setStockCode("UNKNOWN");
      when(poolRepository.findByStockCode("UNKNOWN")).thenReturn(Optional.empty());

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> calculator.recomputeAggregates(pos))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("UNKNOWN");
    }
  }

  // ===== helpers =====

  private PotentialPool poolWithId(Integer id, String code) {
    PotentialPool pool = new PotentialPool();
    pool.setId(id);
    pool.setStockCode(code);
    return pool;
  }

  private PotentialPositionFill fill(Long id, String action, String price, String lots, LocalDateTime at) {
    PotentialPositionFill f = new PotentialPositionFill();
    f.setId(id);
    f.setAction(action);
    f.setPrice(new BigDecimal(price));
    if (lots != null) {
      f.setLots(new BigDecimal(lots));
    }
    f.setFilledAt(at);
    return f;
  }
}