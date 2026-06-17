package com.quant.service;

import com.quant.config.InvestBigYangProperties;
import com.quant.entity.InvestBigYangSignal;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestBigYangSignalRepository;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvestBigYangSignalService")
class InvestBigYangSignalServiceTest {

    @Mock
    private InvestBigYangSignalRepository signalRepository;

    @Mock
    private InvestStockPoolRepository poolRepository;

    @Mock
    private InvestAlertRepository alertRepository;

    @Mock
    private TradeStockDailyRepository dailyRepository;

    @Mock
    private EastMoneyRealtimeQuoteService eastMoneyRealtimeQuoteService;

    @Mock
    private SinaRealtimeQuoteService sinaRealtimeQuoteService;

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
        service = new InvestBigYangSignalService(
                signalRepository,
                poolRepository,
                alertRepository,
                dailyRepository,
                eastMoneyRealtimeQuoteService,
                sinaRealtimeQuoteService,
                props
        );
    }

    @Test
    @DisplayName("连续一字涨停后进入观察池")
    void createsWatchingSignalAfterLimitUpStreak() {
        InvestStockPool pool = pool("000001.SZ", "平安银行");
        when(poolRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pool));
        when(signalRepository.existsByStockCodeAndSignalStatus("000001.SZ", InvestBigYangSignalService.SIGNAL_STATUS_WATCHING))
                .thenReturn(false);
        when(signalRepository.findByStockCodeAndFirstLimitUpDate("000001.SZ", LocalDate.of(2026, 6, 11)))
                .thenReturn(Optional.empty());
        when(dailyRepository.findTop30ByStockCodeOrderByTradeDateDesc("000001.SZ")).thenReturn(List.of(
                daily("000001.SZ", "2026-06-12", "11.20", "11.30", "11.05", "11.20"),
                daily("000001.SZ", "2026-06-11", "10.10", "10.98", "10.10", "10.98"),
                daily("000001.SZ", "2026-06-10", "10.00", "10.20", "9.95", "10.00")
        ));

        int created = service.scanCandidatesInternal();

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<InvestBigYangSignal> captor = ArgumentCaptor.forClass(InvestBigYangSignal.class);
        verify(signalRepository).save(captor.capture());
        InvestBigYangSignal saved = captor.getValue();
        assertThat(saved.getSignalStatus()).isEqualTo(InvestBigYangSignalService.SIGNAL_STATUS_WATCHING);
        assertThat(saved.getLimitUpStreak()).isEqualTo(1);
        assertThat(saved.getFirstLimitUpDate()).isEqualTo(LocalDate.of(2026, 6, 11));
        assertThat(saved.getBaseStartPrice()).isEqualByComparingTo("10.10");
    }

    @Test
    @DisplayName("价格回踩起涨点附近时触发买点提示")
    void triggersAlertWhenPricePullsBackToBase() {
        InvestBigYangSignal signal = watchingSignal();
        when(signalRepository.findTop200BySignalStatusOrderByUpdatedAtDescIdDesc(InvestBigYangSignalService.SIGNAL_STATUS_WATCHING))
                .thenReturn(List.of(signal));
        when(poolRepository.findAllById(anyCollection())).thenReturn(List.of(pool("000001.SZ", "平安银行")));
        when(eastMoneyRealtimeQuoteService.fetch(anyList())).thenReturn(Map.of("000001.SZ", quote("000001.SZ", "10.18")));
        when(dailyRepository.findLatestByStockCodes(anyCollection())).thenReturn(List.of(
                daily("000001.SZ", "2026-06-17", "10.60", "10.70", "10.10", "10.55")
        ));
        when(dailyRepository.findByStockCodeAndTradeDateGreaterThanOrderByTradeDateAsc("000001.SZ", LocalDate.of(2026, 6, 11)))
                .thenReturn(List.of(
                        daily("000001.SZ", "2026-06-12", "10.60", "10.70", "10.50", "10.55"),
                        daily("000001.SZ", "2026-06-13", "10.50", "10.60", "10.30", "10.40")
                ));

        InvestBigYangSignalService.TriggerResult result = service.scanTriggersInternal();

        assertThat(result.triggeredCount()).isEqualTo(1);
        assertThat(result.expiredCount()).isEqualTo(0);
        ArgumentCaptor<InvestBigYangSignal> signalCaptor = ArgumentCaptor.forClass(InvestBigYangSignal.class);
        verify(signalRepository).save(signalCaptor.capture());
        assertThat(signalCaptor.getValue().getSignalStatus()).isEqualTo(InvestBigYangSignalService.SIGNAL_STATUS_TRIGGERED);
        verify(alertRepository).save(any());
    }

    @Test
    @DisplayName("跌破起涨点保护线时失效，不再生成提示")
    void expiresSignalWhenPriceBreaksBelowBase() {
        InvestBigYangSignal signal = watchingSignal();
        when(signalRepository.findTop200BySignalStatusOrderByUpdatedAtDescIdDesc(InvestBigYangSignalService.SIGNAL_STATUS_WATCHING))
                .thenReturn(List.of(signal));
        when(poolRepository.findAllById(anyCollection())).thenReturn(List.of(pool("000001.SZ", "平安银行")));
        when(eastMoneyRealtimeQuoteService.fetch(anyList())).thenReturn(Map.of("000001.SZ", quote("000001.SZ", "9.40")));
        when(dailyRepository.findLatestByStockCodes(anyCollection())).thenReturn(List.of());

        InvestBigYangSignalService.TriggerResult result = service.scanTriggersInternal();

        assertThat(result.triggeredCount()).isEqualTo(0);
        assertThat(result.expiredCount()).isEqualTo(1);
        ArgumentCaptor<InvestBigYangSignal> signalCaptor = ArgumentCaptor.forClass(InvestBigYangSignal.class);
        verify(signalRepository).save(signalCaptor.capture());
        assertThat(signalCaptor.getValue().getSignalStatus()).isEqualTo(InvestBigYangSignalService.SIGNAL_STATUS_EXPIRED);
        verify(alertRepository, never()).save(any());
    }

    private InvestStockPool pool(String stockCode, String stockName) {
        InvestStockPool pool = new InvestStockPool();
        pool.setId(1);
        pool.setStockCode(stockCode);
        pool.setStockName(stockName);
        pool.setPoolType("quality");
        pool.setStatus("watching");
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

    private TradeStockDaily daily(String stockCode, String date, String open, String high, String low, String close) {
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
        TechAiQuoteSnapshot snapshot = new TechAiQuoteSnapshot();
        snapshot.setStockCode(stockCode);
        snapshot.setLatestPrice(new BigDecimal(latestPrice));
        return snapshot;
    }
}
