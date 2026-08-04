package com.quant.service.etfmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.dto.etfmodel.EtfTradeRequest;
import com.quant.dto.etfmodel.EtfTradeResult;
import com.quant.entity.EtfModelConfig;
import com.quant.entity.EtfPool;
import com.quant.entity.EtfTrade;
import com.quant.repository.EtfModelConfigRepository;
import com.quant.repository.EtfNavSnapshotRepository;
import com.quant.repository.EtfPoolRepository;
import com.quant.repository.EtfTradeRepository;

@ExtendWith(MockitoExtension.class)
class EtfModelServiceTest {

  @Mock private EtfPoolRepository poolRepo;
  @Mock private EtfTradeRepository tradeRepo;
  @Mock private EtfModelConfigRepository configRepo;
  @Mock private EtfNavSnapshotRepository navRepo;

  @InjectMocks private EtfModelService service;

  private EtfPool pool;
  private EtfModelConfig config;

  @BeforeEach
  void setUp() {
    pool = new EtfPool();
    pool.setId(1L);
    pool.setStockCode("513100.SH");
    pool.setStockName("纳指ETF");
    pool.setCategory(EtfPool.CATEGORY_BROAD);

    config = new EtfModelConfig();
    config.setId(1L);
    config.setTotalCapital(new BigDecimal("100000"));
    config.setSingleMaxPct(new BigDecimal("20"));
    config.setPortfolioMaxPct(new BigDecimal("70"));
    config.setLightBatchMaxAmount(new BigDecimal("5000"));
    config.setMidBatchMinAmount(new BigDecimal("10000"));
    config.setMidBatchMaxAmount(new BigDecimal("20000"));
    config.setBigRiseThresholdPct(new BigDecimal("15"));
    config.setPortfolioDrawdownPct(new BigDecimal("20"));
    config.setCalmDays(7);
    config.setInceptionDate(LocalDate.of(2026, 6, 23));
  }

  private EtfTrade trade(String dir, String type, String price, int shares, String amount) {
    EtfTrade t = new EtfTrade();
    t.setPoolId(1L);
    t.setStockCode("513100.SH");
    t.setDirection(dir);
    t.setTradeType(type);
    t.setPrice(new BigDecimal(price));
    t.setShares(shares);
    t.setAmount(new BigDecimal(amount));
    t.setTradeTime(LocalDateTime.now());
    return t;
  }

  /* ─────────── 摊薄成本核算 ─────────── */

  @Test
  void dilutedCostFromBuysAndSells() {
    when(tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(1L))
        .thenReturn(
            List.of(
                trade("BUY", EtfTrade.TYPE_OPEN, "1.000", 10000, "10000.00"),
                trade("BUY", EtfTrade.TYPE_ADD, "1.200", 5000, "6000.00"),
                trade("SELL", EtfTrade.TYPE_TP1, "1.200", 5000, "6000.00")));

    EtfPositionView view = service.positionView(pool);

    assertThat(view.getShares()).isEqualTo(10000);
    assertThat(view.getNetInvested()).isEqualByComparingTo("10000.00");
    // 摊薄成本 = 净投入 10000 / 10000 份 = 1.000（卖出回款摊薄了成本）
    assertThat(view.getDilutedCost()).isEqualByComparingTo("1.000");
    assertThat(view.getBatchesUsed()).isEqualTo(2);
  }

  @Test
  void clearanceStartsNewCycle() {
    when(tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(1L))
        .thenReturn(
            List.of(
                trade("BUY", EtfTrade.TYPE_OPEN, "1.000", 10000, "10000.00"),
                trade("SELL", EtfTrade.TYPE_TRAIL_EXIT, "1.100", 10000, "11000.00"),
                trade("BUY", EtfTrade.TYPE_OPEN, "0.900", 5000, "4500.00")));

    EtfPositionView view = service.positionView(pool);

    // 清仓后新周期：批次、净投入从头计
    assertThat(view.getShares()).isEqualTo(5000);
    assertThat(view.getNetInvested()).isEqualByComparingTo("4500.00");
    assertThat(view.getDilutedCost()).isEqualByComparingTo("0.900");
    assertThat(view.getBatchesUsed()).isEqualTo(1);
  }

  @Test
  void profitPctFromDilutedCost() {
    when(tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(1L))
        .thenReturn(List.of(trade("BUY", EtfTrade.TYPE_OPEN, "1.000", 10000, "10000.00")));

    EtfPositionView view = service.positionView(pool);

    assertThat(view.profitPct(new BigDecimal("1.050"))).isEqualByComparingTo("5.00");
    assertThat(view.profitPct(new BigDecimal("0.850"))).isEqualByComparingTo("-15.00");
  }

  /* ─────────── 录单校验 ─────────── */

  @Test
  void sellingMoreThanHeldIsRejected() {
    when(poolRepo.findById(1L)).thenReturn(Optional.of(pool));
    when(tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(1L))
        .thenReturn(List.of(trade("BUY", EtfTrade.TYPE_OPEN, "1.000", 5000, "5000.00")));

    EtfTradeRequest req = new EtfTradeRequest();
    req.setPoolId(1L);
    req.setDirection("SELL");
    req.setTradeType(EtfTrade.TYPE_TP1);
    req.setPrice(new BigDecimal("1.100"));
    req.setShares(6000);

    assertThatThrownBy(() -> service.recordTrade(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过当前持有");
  }

  @Test
  void fourthBatchBuyYieldsWarning() {
    when(poolRepo.findById(1L)).thenReturn(Optional.of(pool));
    when(poolRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(tradeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(configRepo.findById(1L)).thenReturn(Optional.of(config));
    when(poolRepo.findByStatusOrderByIdAsc(EtfPool.STATUS_ACTIVE)).thenReturn(List.of(pool));
    when(tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(1L))
        .thenReturn(
            List.of(
                trade("BUY", EtfTrade.TYPE_OPEN, "1.000", 3000, "3000.00"),
                trade("BUY", EtfTrade.TYPE_ADD, "1.000", 3000, "3000.00"),
                trade("BUY", EtfTrade.TYPE_ADD, "1.000", 3000, "3000.00")));

    EtfTradeRequest req = new EtfTradeRequest();
    req.setPoolId(1L);
    req.setDirection("BUY");
    req.setTradeType(EtfTrade.TYPE_ADD);
    req.setPrice(new BigDecimal("1.000"));
    req.setShares(3000);

    EtfTradeResult result = service.recordTrade(req);

    assertThat(result.warnings()).anyMatch(w -> w.contains("1建仓+2加仓"));
  }

  @Test
  void singleCapExceededYieldsWarning() {
    when(poolRepo.findById(1L)).thenReturn(Optional.of(pool));
    when(poolRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(tradeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(configRepo.findById(1L)).thenReturn(Optional.of(config));
    when(poolRepo.findByStatusOrderByIdAsc(EtfPool.STATUS_ACTIVE)).thenReturn(List.of(pool));
    when(tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(1L))
        .thenReturn(List.of(trade("BUY", EtfTrade.TYPE_OPEN, "1.000", 15000, "15000.00")));

    EtfTradeRequest req = new EtfTradeRequest();
    req.setPoolId(1L);
    req.setDirection("BUY");
    req.setTradeType(EtfTrade.TYPE_ADD);
    req.setPrice(new BigDecimal("1.000"));
    req.setShares(8000); // 净投入 15000 + 8000 = 23000 > 20000（20%）

    EtfTradeResult result = service.recordTrade(req);

    assertThat(result.warnings()).anyMatch(w -> w.contains("单支"));
  }

  @Test
  void normalBuyHasNoWarnings() {
    when(poolRepo.findById(1L)).thenReturn(Optional.of(pool));
    when(poolRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(tradeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(configRepo.findById(1L)).thenReturn(Optional.of(config));
    when(poolRepo.findByStatusOrderByIdAsc(EtfPool.STATUS_ACTIVE)).thenReturn(List.of(pool));
    when(tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(1L)).thenReturn(List.of());

    EtfTradeRequest req = new EtfTradeRequest();
    req.setPoolId(1L);
    req.setDirection("BUY");
    req.setTradeType(EtfTrade.TYPE_OPEN);
    req.setPrice(new BigDecimal("1.000"));
    req.setShares(5000);

    EtfTradeResult result = service.recordTrade(req);

    assertThat(result.warnings()).isEmpty();
    assertThat(result.trade().getAmount()).isEqualByComparingTo("5000.00");
  }

  /* ─────────── 档位/回补状态流转（流水重放） ─────────── */

  @Test
  void slSellMarksTierDoneAndRecoupWaiting() {
    when(poolRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(1L))
        .thenReturn(
            List.of(
                trade("BUY", EtfTrade.TYPE_OPEN, "1.000", 10000, "10000.00"),
                trade("SELL", EtfTrade.TYPE_SL1, "0.850", 5000, "4250.00")));

    service.replayLedger(pool);

    assertThat(pool.getSl1Done()).isEqualTo(1);
    assertThat(pool.getRecoupStatus()).isEqualTo(EtfPool.RECOUP_WAITING);
  }

  @Test
  void recoupBuyResetsRecoupStatus() {
    when(poolRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(1L))
        .thenReturn(
            List.of(
                trade("BUY", EtfTrade.TYPE_OPEN, "1.000", 10000, "10000.00"),
                trade("SELL", EtfTrade.TYPE_SL1, "0.850", 5000, "4250.00"),
                trade("BUY", EtfTrade.TYPE_RECOUP, "0.900", 3000, "2700.00")));

    service.replayLedger(pool);

    assertThat(pool.getRecoupStatus()).isEqualTo(EtfPool.RECOUP_NONE);
  }

  @Test
  void sectorClearanceViaSl2KeepsRecoupWaiting() {
    // 行业 -18% 清仓后仍可周K平稳回补
    when(poolRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(1L))
        .thenReturn(
            List.of(
                trade("BUY", EtfTrade.TYPE_OPEN, "1.000", 10000, "10000.00"),
                trade("SELL", EtfTrade.TYPE_SL2, "0.820", 10000, "8200.00")));

    service.replayLedger(pool);

    assertThat(pool.getRecoupStatus()).isEqualTo(EtfPool.RECOUP_WAITING);
    // 清仓 → 档位标志重置（新周期）
    assertThat(pool.getTp1Done()).isZero();
    assertThat(pool.getSl1Done()).isZero();
  }

  @Test
  void trailExitClearanceResetsEverything() {
    when(poolRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(1L))
        .thenReturn(
            List.of(
                trade("BUY", EtfTrade.TYPE_OPEN, "1.000", 9000, "9000.00"),
                trade("SELL", EtfTrade.TYPE_TP1, "1.050", 3000, "3150.00"),
                trade("SELL", EtfTrade.TYPE_TP2, "1.100", 3000, "3300.00"),
                trade("SELL", EtfTrade.TYPE_TRAIL_EXIT, "1.080", 3000, "3240.00")));

    service.replayLedger(pool);

    assertThat(pool.getTp1Done()).isZero();
    assertThat(pool.getTp2Done()).isZero();
    assertThat(pool.getRecoupStatus()).isEqualTo(EtfPool.RECOUP_NONE);
  }

  /* ─────────── 冷静期 ─────────── */

  @Test
  void calmPeriodDetection() {
    config.setCalmUntil(LocalDate.now().plusDays(3));
    when(configRepo.findById(1L)).thenReturn(Optional.of(config));
    assertThat(service.inCalmPeriod()).isTrue();

    config.setCalmUntil(LocalDate.now().minusDays(1));
    assertThat(service.inCalmPeriod()).isFalse();
  }
}
