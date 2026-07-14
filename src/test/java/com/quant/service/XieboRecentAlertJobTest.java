package com.quant.service;

import com.quant.config.XieboRecentAlertProperties;
import com.quant.entity.InvestAlert;
import com.quant.entity.UserStockSubscription;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.UserStockSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class XieboRecentAlertJobTest {

    @Mock UserStockSubscriptionRepository subRepo;
    @Mock AStockDataQuoteService quoteService;
    @Mock NotificationService notificationService;
    @Mock InvestAlertRepository alertRepo;

    XieboRecentAlertProperties props;
    XieboRecentAlertJob job;

    @BeforeEach
    void setUp() {
        props = new XieboRecentAlertProperties();
        props.setEnabled(true);
        job = new XieboRecentAlertJob(subRepo, quoteService, notificationService, alertRepo, props);
    }

    private UserStockSubscription makeSub(Long userId, String code, BigDecimal buy) {
        UserStockSubscription s = new UserStockSubscription();
        s.setId(1L);
        s.setUserId(userId);
        s.setStockCode(code);
        s.setEnabled(true);
        s.setStatus("关注");
        s.setServerchanSendKey("default-key");
        s.setPriceBuy(buy);
        return s;
    }

    private AStockDataQuoteService.QuoteSnapshot quote(String code, String price) {
        return new AStockDataQuoteService.QuoteSnapshot(
                code, new BigDecimal(price), new BigDecimal("100"),
                null, LocalDateTime.now(), "tencent");
    }

    @Test
    void scan_disabledProps_skips() {
        props.setEnabled(false);
        job.scan();
        verifyNoInteractions(subRepo);
    }

    @Test
    void scan_buyPriceReached_triggersAndPersistsAlert() {
        UserStockSubscription s = makeSub(7L, "600519", new BigDecimal("1850"));
        when(subRepo.findAllEnabledWithPrice()).thenReturn(List.of(s));
        when(quoteService.fetchQuotes(List.of("600519")))
                .thenReturn(Map.of("600519", quote("600519", "1840")));
        when(notificationService.sendServerChan(anyString(), anyString(), anyString()))
                .thenReturn(true);

        job.scan();

        ArgumentCaptor<InvestAlert> captor = ArgumentCaptor.forClass(InvestAlert.class);
        verify(alertRepo).save(captor.capture());
        assertThat(captor.getValue().getStockCode()).isEqualTo("600519");
        assertThat(captor.getValue().getSignalType()).isEqualTo("xiebo_recent_buy");
        assertThat(s.getAlertBuyTriggeredAt()).isNotNull();
        verify(notificationService).sendServerChan(anyString(), contains("买入"), eq("default-key"));
    }

    @Test
    void scan_reducePriceReached_triggersReduceSignal() {
        UserStockSubscription s = new UserStockSubscription();
        s.setId(1L); s.setUserId(7L); s.setStockCode("600519");
        s.setEnabled(true); s.setStatus("建仓");
        s.setServerchanSendKey("default-key");
        s.setPriceReducePosition(new BigDecimal("1950"));
        when(subRepo.findAllEnabledWithPrice()).thenReturn(List.of(s));
        when(quoteService.fetchQuotes(List.of("600519")))
                .thenReturn(Map.of("600519", quote("600519", "1960")));
        when(notificationService.sendServerChan(anyString(), anyString(), anyString()))
                .thenReturn(true);

        job.scan();

        verify(alertRepo).save(argThat(a -> a.getSignalType().equals("xiebo_recent_reduce")));
        assertThat(s.getAlertReducePositionTriggeredAt()).isNotNull();
    }

    @Test
    void scan_alreadyTriggered_doesNotReTrigger() {
        UserStockSubscription s = makeSub(7L, "600519", new BigDecimal("1850"));
        s.setAlertBuyTriggeredAt(LocalDateTime.now().minusMinutes(10));
        when(subRepo.findAllEnabledWithPrice()).thenReturn(List.of(s));
        when(quoteService.fetchQuotes(List.of("600519")))
                .thenReturn(Map.of("600519", quote("600519", "1840")));

        job.scan();

        verify(alertRepo, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void scan_noSckey_writesAlertButDoesNotPush() {
        UserStockSubscription s = new UserStockSubscription();
        s.setId(1L); s.setUserId(7L); s.setStockCode("600519");
        s.setEnabled(true); s.setStatus("关注");
        s.setPriceBuy(new BigDecimal("1850"));
        // 没有 serverchanSendKey
        when(subRepo.findAllEnabledWithPrice()).thenReturn(List.of(s));
        when(quoteService.fetchQuotes(List.of("600519")))
                .thenReturn(Map.of("600519", quote("600519", "1840")));

        job.scan();

        verify(alertRepo).save(any(InvestAlert.class));
        verifyNoInteractions(notificationService);
    }

    @Test
    void scan_quoteMissing_skipsSubscription() {
        UserStockSubscription s = makeSub(7L, "600519", new BigDecimal("1850"));
        when(subRepo.findAllEnabledWithPrice()).thenReturn(List.of(s));
        when(quoteService.fetchQuotes(List.of("600519"))).thenReturn(Map.of());

        job.scan();

        verify(alertRepo, never()).save(any());
    }

    @Test
    void scan_emptySubscriptions_returnsZero() {
        when(subRepo.findAllEnabledWithPrice()).thenReturn(List.of());
        int n = job.scan();
        assertThat(n).isZero();
        verifyNoInteractions(quoteService);
    }
}
