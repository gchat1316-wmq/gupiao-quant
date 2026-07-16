package com.quant.service.monitor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.config.NotificationProperties;
import com.quant.entity.InvestAlert;
import com.quant.entity.InvestPositionCommon;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;
import com.quant.service.notification.NotificationService;
import com.quant.service.techai.TechAiAtrCalculator;

@ExtendWith(MockitoExtension.class)
class MonitorServiceTest {

  @Mock private InvestPositionCommonRepository posRepo;
  @Mock private InvestAlertRepository alertRepo;
  @Mock private TradeStockDailyRepository dailyRepo;
  @Mock private AStockDataQuoteService quoteService;
  @Mock private NotificationService notificationService;
  @Mock private MonitorRuleEngine ruleEngine;
  @Mock private TechAiAtrCalculator atrCalculator;

  private MonitorService service;

  @BeforeEach
  void setUp() {
    NotificationProperties props = new NotificationProperties();
    // ensure monitor section is enabled
    props.getMonitor().setEnabled(true);
    props.getMonitor().setRequireTradingTime(false); // 测试中跳过 trading time 闸门
    service =
        new MonitorService(
            posRepo,
            alertRepo,
            dailyRepo,
            quoteService,
            notificationService,
            ruleEngine,
            atrCalculator,
            props);
  }

  private InvestPositionCommon fixedBuyEnabled(String code) {
    InvestPositionCommon p = new InvestPositionCommon();
    p.setStockCode(code);
    p.setPoolType("tech_ai");
    p.setFixedBuyPrice(new BigDecimal("1500.00"));
    p.setFixedBuyEnabled(1);
    return p;
  }

  private AStockDataQuoteService.QuoteSnapshot quote(String code) {
    return new AStockDataQuoteService.QuoteSnapshot(
        code,
        new BigDecimal("1480.00"),
        new BigDecimal("1500.00"),
        null,
        LocalDateTime.now(),
        "tencent");
  }

  @Test
  void scanInvokesQuoteFetchAndRuleEngineForEachWatchedStock() {
    InvestPositionCommon p = fixedBuyEnabled("600519.SH");
    when(posRepo.findByPoolType("tech_ai")).thenReturn(List.of(p));
    when(quoteService.fetchQuotes(anyList())).thenReturn(Map.of("600519.SH", quote("600519.SH")));
    when(ruleEngine.evaluate(any()))
        .thenReturn(
            List.of(
                MonitorSignal.fixedPriceBuy(
                    p, "600519.SH", "贵州茅台", new BigDecimal("1480.00"), new BigDecimal("1500.00"))));
    when(notificationService.sendServerChan(anyString(), anyString())).thenReturn(true);
    when(alertRepo.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(anyString(), anyString()))
        .thenReturn(Optional.empty());

    int triggered = service.scan("tech_ai");

    assertTrue(triggered >= 1);
    verify(posRepo).findByPoolType("tech_ai");
    verify(ruleEngine, atLeastOnce()).evaluate(any());
  }

  @Test
  void cooldownSuppressesDuplicateSignalWithinWindow() {
    InvestPositionCommon p = fixedBuyEnabled("600519.SH");
    when(posRepo.findByPoolType("tech_ai")).thenReturn(List.of(p));
    when(quoteService.fetchQuotes(anyList())).thenReturn(Map.of("600519.SH", quote("600519.SH")));
    when(ruleEngine.evaluate(any()))
        .thenReturn(
            List.of(
                MonitorSignal.fixedPriceBuy(
                    p, "600519.SH", "贵州茅台", new BigDecimal("1480.00"), new BigDecimal("1500.00"))));
    // 上一次推送发生在 2 分钟前，cooldown 是 10 分钟 → 在冷却内
    InvestAlert recent = new InvestAlert();
    recent.setTriggerAt(LocalDateTime.now().minusMinutes(2));
    when(alertRepo.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(
            "600519.SH", "fixed_buy_hit"))
        .thenReturn(Optional.of(recent));

    service.scan("tech_ai");

    verify(notificationService, never()).sendServerChan(anyString(), anyString());
  }

  @Test
  void serverchanFailureDoesNotThrow() {
    InvestPositionCommon p = fixedBuyEnabled("600519.SH");
    when(posRepo.findByPoolType("tech_ai")).thenReturn(List.of(p));
    when(quoteService.fetchQuotes(anyList())).thenReturn(Map.of("600519.SH", quote("600519.SH")));
    when(ruleEngine.evaluate(any()))
        .thenReturn(
            List.of(
                MonitorSignal.fixedPriceBuy(
                    p, "600519.SH", "贵州茅台", new BigDecimal("1480.00"), new BigDecimal("1500.00"))));
    when(notificationService.sendServerChan(anyString(), anyString())).thenReturn(false);
    when(alertRepo.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(anyString(), anyString()))
        .thenReturn(Optional.empty());

    assertDoesNotThrow(() -> service.scan("tech_ai"));
  }

  @Test
  void disabledFlagSuppressesSignal() {
    InvestPositionCommon p = fixedBuyEnabled("600519.SH");
    p.setFixedBuyEnabled(0); // disabled
    when(posRepo.findByPoolType("tech_ai")).thenReturn(List.of(p));
    when(quoteService.fetchQuotes(anyList())).thenReturn(Map.of("600519.SH", quote("600519.SH")));
    // rule engine 返回空 (disabled 时不会产信号)
    when(ruleEngine.evaluate(any())).thenReturn(List.of());

    int triggered = service.scan("tech_ai");

    assertTrue(triggered == 0);
    verify(ruleEngine, atLeastOnce()).evaluate(any());
    verify(notificationService, never()).sendServerChan(anyString(), anyString());
  }

  @Test
  void cooldownBoundaryAllowsTriggerAfterWindow() {
    InvestPositionCommon p = fixedBuyEnabled("600519.SH");
    when(posRepo.findByPoolType("tech_ai")).thenReturn(List.of(p));
    when(quoteService.fetchQuotes(anyList())).thenReturn(Map.of("600519.SH", quote("600519.SH")));
    when(ruleEngine.evaluate(any()))
        .thenReturn(
            List.of(
                MonitorSignal.fixedPriceBuy(
                    p, "600519.SH", "贵州茅台", new BigDecimal("1480.00"), new BigDecimal("1500.00"))));

    // 12 分钟前推送，cooldown 10 → 已超过
    InvestAlert old = new InvestAlert();
    old.setTriggerAt(LocalDateTime.now().minusMinutes(12));
    when(alertRepo.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(
            "600519.SH", "fixed_buy_hit"))
        .thenReturn(Optional.of(old));
    when(notificationService.sendServerChan(anyString(), anyString())).thenReturn(true);

    service.scan("tech_ai");

    ArgumentCaptor<InvestAlert> cap = ArgumentCaptor.forClass(InvestAlert.class);
    verify(alertRepo).save(cap.capture());
    assertTrue(cap.getValue().getPushed() == 1, "should have pushed successfully");
  }
}
