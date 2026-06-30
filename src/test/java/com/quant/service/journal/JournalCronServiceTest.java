package com.quant.service.journal;

import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import com.quant.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JournalCronServiceTest {

    @Mock JournalTradeRepository repo;
    @Mock NotificationService notificationService;
    @InjectMocks JournalCronService cron;

    @Test
    void refreshOpenTrades_autoClosesOnTargetHit() {
        var open = new JournalTrade();
        open.setId(1L);
        open.setMode(JournalTrade.Mode.REAL);
        open.setStockCode("600519");
        open.setStockName("贵州茅台");
        open.setEntryPrice(new BigDecimal("100"));
        open.setStopPrice(new BigDecimal("95"));
        open.setTargetPrice(new BigDecimal("115"));
        open.setInitialRisk(new BigDecimal("5.00"));
        open.setEntryShares(100);
        open.setIsOpen(1);

        when(repo.findAllOpen()).thenReturn(List.of(open));
        when(repo.save(any(JournalTrade.class))).thenAnswer(inv -> inv.getArgument(0));

        // current price 120 > target 115 -> should auto-close
        cron.refreshOpenTrades("600519", new BigDecimal("120"));

        ArgumentCaptor<JournalTrade> cap = ArgumentCaptor.forClass(JournalTrade.class);
        verify(repo).save(cap.capture());
        var saved = cap.getValue();
        assertEquals(0, saved.getIsOpen());
        assertEquals(JournalTrade.ExitReason.target_hit, saved.getExitReason());
        assertEquals(0, saved.getExitPrice().compareTo(new BigDecimal("115")));

        // Verify Server酱 was notified
        verify(notificationService).sendServerChan(any(), any());
    }

    @Test
    void refreshOpenTrades_doesNotCloseWhenBelowTarget() {
        var open = new JournalTrade();
        open.setId(2L);
        open.setMode(JournalTrade.Mode.REAL);
        open.setStockCode("600519");
        open.setStockName("贵州茅台");
        open.setEntryPrice(new BigDecimal("100"));
        open.setStopPrice(new BigDecimal("95"));
        open.setTargetPrice(new BigDecimal("115"));
        open.setInitialRisk(new BigDecimal("5.00"));
        open.setEntryShares(100);
        open.setIsOpen(1);

        when(repo.findAllOpen()).thenReturn(List.of(open));

        // current price 110 < target 115 -> should NOT close
        cron.refreshOpenTrades("600519", new BigDecimal("110"));

        verify(repo, never()).save(any());
        verify(notificationService, never()).sendServerChan(any(), any());
    }
}
