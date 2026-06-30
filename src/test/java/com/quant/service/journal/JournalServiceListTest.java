package com.quant.service.journal;

import com.quant.entity.InvestPositionFill;
import com.quant.entity.JournalTrade;
import com.quant.repository.InvestPositionFillRepository;
import com.quant.repository.JournalTradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JournalServiceListTest {

    @Mock JournalTradeRepository repo;
    @Mock InvestPositionFillRepository fillRepo;
    @InjectMocks JournalService service;

    @Test
    void list_passesFiltersToRepo() {
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        var page = service.list("PAPER", true, null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20));
        assertNotNull(page);
        verify(repo).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void listOpen_returnsOpenTrades() {
        var j = new JournalTrade();
        j.setId(1L);
        j.setMode(JournalTrade.Mode.REAL);
        when(repo.findAllOpen()).thenReturn(List.of(j));
        var result = service.listOpen();
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void pendingFills_excludesAlreadySynced() {
        var fill = new InvestPositionFill();
        fill.setId(10L);
        fill.setStockCode("600519");
        fill.setAction("clear");
        fill.setPrice(new BigDecimal("110"));
        fill.setLots(new BigDecimal("2"));
        fill.setFilledAt(LocalDateTime.of(2026, 6, 30, 15, 0));

        when(fillRepo.findRecentSince(any(LocalDateTime.class))).thenReturn(List.of(fill));
        when(repo.findBySourceRef(10L)).thenReturn(Optional.empty());

        var out = service.pendingFills();
        assertEquals(1, out.size());
        assertEquals(10L, out.get(0).getFillId());
    }
}