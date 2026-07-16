package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.config.InvestBigYangProperties;
import com.quant.entity.InvestBigYangSignal;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestBigYangSignalRepository;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockDailyRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvestBigYangSignalService")
class InvestBigYangSignalServiceTest {

  @Mock private InvestBigYangSignalRepository signalRepository;

  @Mock private InvestStockPoolRepository poolRepository;

  @Mock private InvestPositionCommonRepository positionRepository;

  @Mock private InvestAlertRepository alertRepository;

  @Mock private TradeStockDailyRepository dailyRepository;

  @Mock private EastMoneyRealtimeQuoteService eastMoneyRealtimeQuoteService;

  @Mock private SinaRealtimeQuoteService sinaRealtimeQuoteService;

  @Mock private AStockDataQuoteService aStockDataQuoteService;

  @Mock private NotificationService notificationService;

  private InvestBigYangSignalService service;

  @BeforeEach
  void setUp() {
    InvestBigYangProperties props = new InvestBigYangProperties();
    props.setEnabled(true);
    props.setMinStreakDays(1);
    props.setMaxStreakDays(2);
    props.setCandidateLookbackDays(20);
    props.setPullbackTolerancePct(BigDecimal.valueOf(2));
    props.setInvalidBreakPct(BigDecimal.valueOf(5));
    props.setExpireTradingDays(20);
    // 默认所有股票 a-stock-data 实时行情为空，迫使 trigger 扫描跳过（与现有 EastMoney/Sina mock 隔离）
    org.mockito.Mockito.lenient()
        .when(aStockDataQuoteService.fetchQuotes(any()))
        .thenReturn(Map.of());
    // signals() 不查实时价，但其它测试 (signalsQuotes) 会调；给个空 Map 默认，避免 strict-stub 报错
    org.mockito.Mockito.lenient()
        .when(eastMoneyRealtimeQuoteService.fetch(anyList()))
        .thenReturn(Map.of());
    org.mockito.Mockito.lenient()
        .when(sinaRealtimeQuoteService.fetch(anyList()))
        .thenReturn(Map.of());
    service =
        new InvestBigYangSignalService(
            signalRepository,
            poolRepository,
            positionRepository,
            alertRepository,
            dailyRepository,
            eastMoneyRealtimeQuoteService,
            sinaRealtimeQuoteService,
            aStockDataQuoteService,
            props,
            notificationService);
  }

  @Test
  @DisplayName("盘后日线未更新时不触发买点提示")
  void skipsTriggerWhenAfterHoursDailyIsStale() {
    service =
        new InvestBigYangSignalService(
            signalRepository,
            poolRepository,
            positionRepository,
            alertRepository,
            dailyRepository,
            eastMoneyRealtimeQuoteService,
            sinaRealtimeQuoteService,
            aStockDataQuoteService,
            freshProps(),
            notificationService) {
          @Override
          LocalDateTime now() {
            return LocalDateTime.of(2026, 6, 17, 21, 8, 0);
          }
        };
    InvestBigYangSignal signal = watchingSignal();
    when(signalRepository.findTop200BySignalStatusOrderByUpdatedAtDescIdDesc(
            InvestBigYangSignalService.SIGNAL_STATUS_WATCHING))
        .thenReturn(List.of(signal));
    when(poolRepository.findAllById(anyCollection()))
        .thenReturn(List.of(pool("000001.SZ", "平安银行")));
    when(eastMoneyRealtimeQuoteService.fetch(anyList()))
        .thenReturn(Map.of("000001.SZ", quote("000001.SZ", "10.18")));
    when(dailyRepository.findLatestByStockCodes(anyCollection()))
        .thenReturn(List.of(daily("000001.SZ", "2026-06-11", "10.60", "10.70", "10.10", "10.55")));

    InvestBigYangSignalService.TriggerResult result = service.scanTriggersInternal();

    assertThat(result.triggeredCount()).isEqualTo(0);
    assertThat(result.expiredCount()).isEqualTo(0);
    verify(alertRepository, never()).save(any());
  }

  @Test
  @DisplayName("信号基础列表不查实时行情（首屏秒级返回）")
  void signalsBasicDoesNotCallRealtimeQuote() {
    InvestBigYangSignal signal = watchingSignal();
    when(signalRepository.findTop200ByOrderByUpdatedAtDescIdDesc()).thenReturn(List.of(signal));

    List<com.quant.dto.invest.BigYangSignalDTO> dtos = service.signals();

    assertThat(dtos).hasSize(1);
    // signals() 只返基础数据，currentPrice/distanceToBasePct 留 null 给前端异步填
    assertThat(dtos.get(0).getCurrentPrice()).isNull();
    assertThat(dtos.get(0).getCurrentPriceDate()).isNull();
    assertThat(dtos.get(0).getDistanceToBasePct()).isNull();
    // 关键断言：signals() 不应该调 realtime quote 服务
    verify(eastMoneyRealtimeQuoteService, never()).fetch(anyList());
    verify(sinaRealtimeQuoteService, never()).fetch(anyList());
  }

  @Test
  @DisplayName("signalsQuotes 端点返回最新实时行情")
  void signalsQuotesExposeLatestDailyDate() {
    InvestBigYangSignal signal = watchingSignal();
    when(signalRepository.findTop200ByOrderByUpdatedAtDescIdDesc()).thenReturn(List.of(signal));
    when(eastMoneyRealtimeQuoteService.fetch(anyList()))
        .thenReturn(
            Map.of("000001.SZ", quote("000001.SZ", "10.66", LocalDateTime.of(2026, 6, 17, 15, 0))));

    List<com.quant.dto.invest.BigYangQuoteDTO> quotes = service.signalsQuotes();

    assertThat(quotes).hasSize(1);
    assertThat(quotes.get(0).getStockCode()).isEqualTo("000001.sz");
    assertThat(quotes.get(0).getCurrentPrice()).isEqualByComparingTo("10.66");
    assertThat(quotes.get(0).getCurrentPriceDate()).isEqualTo(LocalDate.of(2026, 6, 17));
  }

  @Test
  @DisplayName("signalsQuotes 东方财富拿不到时回退新浪，新浪也无则返空")
  void signalsQuotesFallbackToSinaAndBack() {
    InvestBigYangSignal signal = watchingSignal();
    when(signalRepository.findTop200ByOrderByUpdatedAtDescIdDesc()).thenReturn(List.of(signal));
    when(eastMoneyRealtimeQuoteService.fetch(anyList())).thenReturn(Map.of());
    when(sinaRealtimeQuoteService.fetch(anyList())).thenReturn(Map.of());

    List<com.quant.dto.invest.BigYangQuoteDTO> quotes = service.signalsQuotes();

    assertThat(quotes).isEmpty();
  }

  @Test
  @DisplayName("连续一字涨停后进入观察池")
  void createsWatchingSignalAfterLimitUpStreak() {
    InvestStockPool pool = pool("000001.SZ", "平安银行");
    when(poolRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pool));
    when(signalRepository.findByStockCodeAndSignalStatus(
            "000001.SZ", InvestBigYangSignalService.SIGNAL_STATUS_WATCHING))
        .thenReturn(Optional.empty());
    when(signalRepository.findByStockCodeAndFirstLimitUpDate(
            "000001.SZ", LocalDate.of(2026, 6, 11)))
        .thenReturn(Optional.empty());
    when(dailyRepository.findRecentKlineBatch(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                daily("000001.SZ", "2026-06-12", "11.20", "11.30", "11.05", "11.20"),
                daily("000001.SZ", "2026-06-11", "10.10", "10.98", "10.10", "10.98"),
                daily("000001.SZ", "2026-06-10", "10.00", "10.20", "9.95", "10.00")));

    int created = service.scanCandidatesInternal();

    assertThat(created).isEqualTo(1);
    ArgumentCaptor<InvestBigYangSignal> captor = ArgumentCaptor.forClass(InvestBigYangSignal.class);
    verify(signalRepository).save(captor.capture());
    InvestBigYangSignal saved = captor.getValue();
    assertThat(saved.getSignalStatus())
        .isEqualTo(InvestBigYangSignalService.SIGNAL_STATUS_WATCHING);
    assertThat(saved.getLimitUpStreak()).isEqualTo(1);
    assertThat(saved.getFirstLimitUpDate()).isEqualTo(LocalDate.of(2026, 6, 11));
    assertThat(saved.getBaseStartPrice()).isEqualByComparingTo("10.10");
  }

  @Test
  @DisplayName("已有观察中信号会按最新日线回算首板开盘价")
  void refreshesWatchingSignalBasePriceFromLatestDaily() {
    InvestStockPool pool = pool("000001.SZ", "平安银行");
    InvestBigYangSignal existing = watchingSignal();
    existing.setBaseStartPrice(new BigDecimal("60.73"));
    existing.setFirstLimitUpOpenPrice(new BigDecimal("60.73"));
    existing.setFirstLimitUpClosePrice(new BigDecimal("66.55"));
    existing.setLastLimitUpClosePrice(new BigDecimal("66.55"));
    when(poolRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pool));
    when(signalRepository.findByStockCodeAndSignalStatus(
            "000001.SZ", InvestBigYangSignalService.SIGNAL_STATUS_WATCHING))
        .thenReturn(Optional.of(existing));
    when(dailyRepository.findRecentKlineBatch(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                daily("000001.SZ", "2026-05-27", "50.99", "57.83", "50.14", "55.02"),
                daily("000001.SZ", "2026-05-26", "53.83", "55.29", "49.60", "52.58"),
                daily("000001.SZ", "2026-05-25", "46.36", "50.80", "46.36", "50.80"),
                daily("000001.SZ", "2026-05-22", "45.37", "46.66", "44.14", "45.81")));

    int created = service.scanCandidatesInternal();

    assertThat(created).isEqualTo(0);
    ArgumentCaptor<InvestBigYangSignal> captor = ArgumentCaptor.forClass(InvestBigYangSignal.class);
    verify(signalRepository).save(captor.capture());
    InvestBigYangSignal saved = captor.getValue();
    assertThat(saved.getBaseStartPrice()).isEqualByComparingTo("46.36");
    assertThat(saved.getFirstLimitUpOpenPrice()).isEqualByComparingTo("46.36");
    assertThat(saved.getFirstLimitUpDate()).isEqualTo(LocalDate.of(2026, 5, 25));
  }

  @Test
  @DisplayName("价格回踩起涨点附近时触发买点提示")
  void triggersAlertWhenPricePullsBackToBase() {
    service =
        new InvestBigYangSignalService(
            signalRepository,
            poolRepository,
            positionRepository,
            alertRepository,
            dailyRepository,
            eastMoneyRealtimeQuoteService,
            sinaRealtimeQuoteService,
            aStockDataQuoteService,
            freshProps(),
            notificationService) {
          @Override
          LocalDateTime now() {
            return LocalDateTime.of(2026, 6, 17, 10, 5, 0);
          }
        };
    InvestBigYangSignal signal = watchingSignal();
    when(signalRepository.findTop200BySignalStatusOrderByUpdatedAtDescIdDesc(
            InvestBigYangSignalService.SIGNAL_STATUS_WATCHING))
        .thenReturn(List.of(signal));
    when(poolRepository.findAllById(anyCollection()))
        .thenReturn(List.of(pool("000001.SZ", "平安银行")));
    when(eastMoneyRealtimeQuoteService.fetch(anyList()))
        .thenReturn(Map.of("000001.SZ", quote("000001.SZ", "10.18")));
    when(dailyRepository.findLatestByStockCodes(anyCollection()))
        .thenReturn(List.of(daily("000001.SZ", "2026-06-17", "10.60", "10.70", "10.10", "10.55")));
    when(dailyRepository.findByStockCodeAndTradeDateGreaterThanOrderByTradeDateAsc(
            "000001.SZ", LocalDate.of(2026, 6, 11)))
        .thenReturn(
            List.of(
                daily("000001.SZ", "2026-06-12", "10.60", "10.70", "10.50", "10.55"),
                daily("000001.SZ", "2026-06-13", "10.50", "10.60", "10.30", "10.40")));

    InvestBigYangSignalService.TriggerResult result = service.scanTriggersInternal();

    assertThat(result.triggeredCount()).isEqualTo(1);
    assertThat(result.expiredCount()).isEqualTo(0);
    ArgumentCaptor<InvestBigYangSignal> signalCaptor =
        ArgumentCaptor.forClass(InvestBigYangSignal.class);
    verify(signalRepository).save(signalCaptor.capture());
    assertThat(signalCaptor.getValue().getSignalStatus())
        .isEqualTo(InvestBigYangSignalService.SIGNAL_STATUS_TRIGGERED);
    verify(alertRepository).save(any());
  }

  @Test
  @DisplayName("跌破起涨点保护线时失效，不再生成提示")
  void expiresSignalWhenPriceBreaksBelowBase() {
    service =
        new InvestBigYangSignalService(
            signalRepository,
            poolRepository,
            positionRepository,
            alertRepository,
            dailyRepository,
            eastMoneyRealtimeQuoteService,
            sinaRealtimeQuoteService,
            aStockDataQuoteService,
            freshProps(),
            notificationService) {
          @Override
          LocalDateTime now() {
            return LocalDateTime.of(2026, 6, 17, 10, 5, 0);
          }
        };
    InvestBigYangSignal signal = watchingSignal();
    when(signalRepository.findTop200BySignalStatusOrderByUpdatedAtDescIdDesc(
            InvestBigYangSignalService.SIGNAL_STATUS_WATCHING))
        .thenReturn(List.of(signal));
    when(poolRepository.findAllById(anyCollection()))
        .thenReturn(List.of(pool("000001.SZ", "平安银行")));
    when(eastMoneyRealtimeQuoteService.fetch(anyList()))
        .thenReturn(Map.of("000001.SZ", quote("000001.SZ", "9.40")));
    when(dailyRepository.findLatestByStockCodes(anyCollection())).thenReturn(List.of());

    InvestBigYangSignalService.TriggerResult result = service.scanTriggersInternal();

    assertThat(result.triggeredCount()).isEqualTo(0);
    assertThat(result.expiredCount()).isEqualTo(1);
    ArgumentCaptor<InvestBigYangSignal> signalCaptor =
        ArgumentCaptor.forClass(InvestBigYangSignal.class);
    verify(signalRepository).save(signalCaptor.capture());
    assertThat(signalCaptor.getValue().getSignalStatus())
        .isEqualTo(InvestBigYangSignalService.SIGNAL_STATUS_EXPIRED);
    verify(alertRepository, never()).save(any());
  }

  private InvestStockPool pool(String stockCode, String stockName) {
    InvestStockPool pool = new InvestStockPool();
    pool.setId(1);
    pool.setStockCode(stockCode);
    pool.setStockName(stockName);
    pool.setPoolType("quality");
    return pool;
  }

  private InvestBigYangSignal watchingSignal() {
    InvestBigYangSignal signal = new InvestBigYangSignal();
    signal.setId(1L);
    signal.setSourcePoolId(1);
    signal.setSourcePoolType("quality");
    signal.setStockCode("000001.SZ");
    signal.setStockName("平安银行");
    signal.setSignalStatus(InvestBigYangSignalService.SIGNAL_STATUS_WATCHING);
    signal.setLimitUpStreak(1);
    signal.setFirstLimitUpDate(LocalDate.of(2026, 6, 11));
    signal.setLastLimitUpDate(LocalDate.of(2026, 6, 11));
    signal.setBaseStartPrice(new BigDecimal("10.10"));
    return signal;
  }

  private InvestBigYangProperties freshProps() {
    InvestBigYangProperties props = new InvestBigYangProperties();
    props.setEnabled(true);
    props.setMinStreakDays(1);
    props.setMaxStreakDays(2);
    props.setCandidateLookbackDays(20);
    props.setPullbackTolerancePct(BigDecimal.valueOf(2));
    props.setInvalidBreakPct(BigDecimal.valueOf(5));
    props.setExpireTradingDays(20);
    return props;
  }

  private TradeStockDaily daily(
      String stockCode, String date, String open, String high, String low, String close) {
    TradeStockDaily daily = new TradeStockDaily();
    daily.setStockCode(stockCode);
    daily.setTradeDate(LocalDate.parse(date));
    daily.setOpenPrice(new BigDecimal(open));
    daily.setHighPrice(new BigDecimal(high));
    daily.setLowPrice(new BigDecimal(low));
    daily.setClosePrice(new BigDecimal(close));
    return daily;
  }

  private TechAiQuoteSnapshot quote(String stockCode, String latestPrice) {
    return quote(stockCode, latestPrice, LocalDateTime.of(2026, 6, 17, 10, 0));
  }

  private TechAiQuoteSnapshot quote(String stockCode, String latestPrice, LocalDateTime quoteTime) {
    TechAiQuoteSnapshot snapshot = new TechAiQuoteSnapshot();
    snapshot.setStockCode(stockCode);
    snapshot.setLatestPrice(new BigDecimal(latestPrice));
    snapshot.setQuoteTime(quoteTime);
    return snapshot;
  }
}
