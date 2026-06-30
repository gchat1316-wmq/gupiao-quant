package com.quant.service.journal;

import com.quant.dto.journal.JournalStatsDTO;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JournalStatsServiceTest {

    @Mock JournalTradeRepository repo;
    @InjectMocks JournalStatsService service;

    private JournalTrade trade(BigDecimal r) {
        var j = new JournalTrade();
        j.setMode(JournalTrade.Mode.REAL);
        j.setRMultiple(r);
        j.setExitDate(LocalDateTime.of(2026, 6, 30, 15, 0));
        return j;
    }

    @Test
    void stats_empty_returnsZeros() {
        when(repo.findAllClosedOrdered()).thenReturn(List.of());
        var s = service.stats(null);
        assertEquals(0, s.getTotalTrades());
        assertEquals(BigDecimal.ZERO, s.getWinRate());
    }

    @Test
    void stats_mixedCalculatesWinRateAndEv() {
        // 4 trades: +3R, +1R, -1R, -1R  →  win_rate=0.5, avg_win=+2, avg_loss=-1
        //  EV = 0.5 * 2 + 0.5 * -1 = 0.5
        when(repo.findAllClosedOrdered()).thenReturn(List.of(
                trade(new BigDecimal("3")),
                trade(new BigDecimal("1")),
                trade(new BigDecimal("-1")),
                trade(new BigDecimal("-1"))));
        var s = service.stats(null);
        assertEquals(4, s.getTotalTrades());
        assertEquals(2, s.getWins());
        assertEquals(2, s.getLosses());
        assertEquals(0, s.getWinRate().compareTo(new BigDecimal("0.5000")));
        assertEquals(0, s.getAverageWinR().compareTo(new BigDecimal("2.0000")));
        assertEquals(0, s.getAverageLossR().compareTo(new BigDecimal("-1.0000")));
        assertEquals(0, s.getExpectedValue().compareTo(new BigDecimal("0.5000")));
    }
}