package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.config.NotificationProperties;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;
import com.quant.service.notification.NotificationDispatcher;
import com.quant.service.notification.PriceMonitorService;

/** PriceMonitorService 单测：覆盖价格监控状态机与通知 fanout 行为。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PriceMonitorService")
class PriceMonitorServiceTest {

  @Mock private InvestStockPoolRepository poolRepository;
  @Mock private InvestPositionCommonRepository positionRepository;
  @Mock private TradeStockBasicRepository basicRepository;
  @Mock private AStockDataQuoteService aStockDataQuoteService;
  @Mock private NotificationDispatcher notificationDispatcher;
  @Mock private com.quant.service.monitor.MonitorService monitorService;

  private NotificationProperties notifProps;
  private PriceMonitorService service;

  @BeforeEach
  void setUp() {
    notifProps = new NotificationProperties();
    notifProps.getPriceMonitor().setEnabled(true);
    notifProps.getPriceMonitor().setRequireTradingTime(false); // 单测里绕过交易时段
    notifProps.getPriceMonitor().setCooldownMinutes(30);
    service =
        new PriceMonitorService(
            poolRepository,
            positionRepository,
            basicRepository,
            aStockDataQuoteService,
            notifProps,
            notificationDispatcher,
            monitorService);
  }

  @Test
  @DisplayName("价格触及目标买入价 → 调用 dispatcher fanout，type=PRICE_BUY_ALERT")
  void triggersBuyAlertAndDispatches() {
    // 持仓 + 目标买入价
    InvestPositionCommon pos = new InvestPositionCommon();
    pos.setStockCode("600519");
    pos.setPoolType("invest");
    pos.setAlertState("none");
    pos.setStatus("watching");
    when(positionRepository.findByPoolType("invest")).thenReturn(List.of(pos));

    InvestStockPool pool = new InvestStockPool();
    pool.setStockCode("600519");
    pool.setTargetBuyPrice(new BigDecimal("1800"));
    when(poolRepository.findByStockCodeIn(anyList())).thenReturn(List.of(pool));

    TradeStockBasic basic = new TradeStockBasic();
    basic.setStockCode("600519");
    basic.setStockName("贵州茅台");
    when(basicRepository.findByStockCodeIn(anyList())).thenReturn(List.of(basic));

    AStockDataQuoteService.QuoteSnapshot snap =
        new AStockDataQuoteService.QuoteSnapshot(
            "600519", new BigDecimal("1750"), null, null, null, null);
    when(aStockDataQuoteService.fetchQuotes(anyList()))
        .thenReturn(java.util.Map.of("600519", snap));

    when(notificationDispatcher.dispatchPriceAlert(
            eq("600519"), eq(PriceMonitorService.TYPE_BUY), anyString(), anyString()))
        .thenReturn(new NotificationDispatcher.DispatchResult(3, 3));

    service.monitorPrices();

    ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
    verify(notificationDispatcher)
        .dispatchPriceAlert(eq("600519"), typeCaptor.capture(), anyString(), anyString());
    assertThat(typeCaptor.getValue()).isEqualTo(PriceMonitorService.TYPE_BUY);
    assertThat(pos.getAlertState()).isEqualTo("buy_alerted");
    assertThat(pos.getLastAlertAt()).isNotNull();
  }

  @Test
  @DisplayName("价格回到正常区间 → 重置 alertState，不调用 dispatcher")
  void resetsAlertStateWhenPriceNormal() {
    InvestPositionCommon pos = new InvestPositionCommon();
    pos.setStockCode("600519");
    pos.setPoolType("invest");
    pos.setAlertState("buy_alerted"); // 之前已被触发
    pos.setStatus("watching");
    when(positionRepository.findByPoolType("invest")).thenReturn(List.of(pos));

    InvestStockPool pool = new InvestStockPool();
    pool.setStockCode("600519");
    pool.setTargetBuyPrice(new BigDecimal("1800"));
    when(poolRepository.findByStockCodeIn(anyList())).thenReturn(List.of(pool));
    when(basicRepository.findByStockCodeIn(anyList())).thenReturn(List.of());

    AStockDataQuoteService.QuoteSnapshot snap =
        new AStockDataQuoteService.QuoteSnapshot(
            "600519", new BigDecimal("1850"), null, null, null, null);
    when(aStockDataQuoteService.fetchQuotes(anyList()))
        .thenReturn(java.util.Map.of("600519", snap));

    service.monitorPrices();

    assertThat(pos.getAlertState()).isEqualTo("none");
    verify(notificationDispatcher, never())
        .dispatchPriceAlert(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("已 exited 的持仓不监控")
  void exitedPositionsAreSkipped() {
    InvestPositionCommon pos = new InvestPositionCommon();
    pos.setStockCode("600519");
    pos.setPoolType("invest");
    pos.setStatus("exited");
    when(positionRepository.findByPoolType("invest")).thenReturn(List.of(pos));

    service.monitorPrices();

    verify(notificationDispatcher, never())
        .dispatchPriceAlert(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("enabled=false → 完全跳过")
  void disabledFlagSkips() {
    notifProps.getPriceMonitor().setEnabled(false);

    service.monitorPrices();

    verify(positionRepository, never()).findByPoolType(anyString());
  }

  @Test
  @DisplayName("价格触及目标卖出价 → type=PRICE_SELL_ALERT")
  void triggersSellAlertAndDispatches() {
    InvestPositionCommon pos = new InvestPositionCommon();
    pos.setStockCode("002371");
    pos.setPoolType("invest");
    pos.setAlertState("none");
    pos.setStatus("holding");
    pos.setTargetSellPrice(new BigDecimal("300"));
    when(positionRepository.findByPoolType("invest")).thenReturn(List.of(pos));

    InvestStockPool pool = new InvestStockPool();
    pool.setStockCode("002371");
    when(poolRepository.findByStockCodeIn(anyList())).thenReturn(List.of(pool));
    when(basicRepository.findByStockCodeIn(anyList())).thenReturn(List.of());

    AStockDataQuoteService.QuoteSnapshot snap =
        new AStockDataQuoteService.QuoteSnapshot(
            "002371", new BigDecimal("305"), null, null, null, null);
    when(aStockDataQuoteService.fetchQuotes(anyList()))
        .thenReturn(java.util.Map.of("002371", snap));

    when(notificationDispatcher.dispatchPriceAlert(
            eq("002371"), eq(PriceMonitorService.TYPE_SELL), anyString(), anyString()))
        .thenReturn(new NotificationDispatcher.DispatchResult(2, 2));

    service.monitorPrices();

    verify(notificationDispatcher)
        .dispatchPriceAlert(
            eq("002371"), eq(PriceMonitorService.TYPE_SELL), anyString(), anyString());
    assertThat(pos.getAlertState()).isEqualTo("sell_alerted");
  }
}
