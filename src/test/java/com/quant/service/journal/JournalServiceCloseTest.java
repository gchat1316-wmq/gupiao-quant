package com.quant.service.journal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.dto.journal.JournalTradeUpdateRequest;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;

@ExtendWith(MockitoExtension.class)
class JournalServiceCloseTest {

  @Mock JournalTradeRepository repo;
  @InjectMocks JournalService service;

  @Test
  void close_computesRMultipleAndPnl() {
    var existing = new JournalTrade();
    existing.setId(1L);
    existing.setMode(JournalTrade.Mode.REAL);
    existing.setEntryPrice(new BigDecimal("100"));
    existing.setStopPrice(new BigDecimal("95"));
    existing.setInitialRisk(new BigDecimal("5.00"));
    existing.setEntryShares(200);
    existing.setIsOpen(1);
    when(repo.findActiveById(1L)).thenReturn(Optional.of(existing));
    when(repo.save(any(JournalTrade.class))).thenAnswer(inv -> inv.getArgument(0));

    var req = new JournalTradeUpdateRequest();
    req.setExitPrice(new BigDecimal("115"));
    req.setExitReason("manual");
    req.setExitDate(LocalDateTime.of(2026, 6, 30, 15, 0));

    var dto = service.update(1L, req);

    // pnl = (115-100)*200 = 3000
    // r = 3000 / (5 * 200) = 3.0
    ArgumentCaptor<JournalTrade> cap = ArgumentCaptor.forClass(JournalTrade.class);
    verify(repo).save(cap.capture());
    var saved = cap.getValue();
    assertEquals(new BigDecimal("3000.00"), saved.getPnlAmount());
    assertEquals(0, saved.getRMultiple().compareTo(new BigDecimal("3.0000")));
    assertEquals(0, saved.getIsOpen());
    assertEquals(JournalTrade.ExitReason.manual, saved.getExitReason());
    assertNotNull(dto.getExitPrice());
  }

  @Test
  void update_stopLossOnly_keepsTradeOpen() {
    var existing = new JournalTrade();
    existing.setId(1L);
    existing.setEntryPrice(new BigDecimal("100"));
    existing.setStopPrice(new BigDecimal("95"));
    existing.setInitialRisk(new BigDecimal("5.00"));
    existing.setEntryShares(100);
    existing.setIsOpen(1);
    when(repo.findActiveById(1L)).thenReturn(Optional.of(existing));
    when(repo.save(any(JournalTrade.class))).thenAnswer(inv -> inv.getArgument(0));

    var req = new JournalTradeUpdateRequest();
    req.setStopPrice(new BigDecimal("97")); // tighten only
    req.setTags("海龟,练习1");

    var dto = service.update(1L, req);

    assertNull(dto.getExitPrice());
    assertTrue(dto.getIsOpen());
    ArgumentCaptor<JournalTrade> cap = ArgumentCaptor.forClass(JournalTrade.class);
    verify(repo).save(cap.capture());
    assertEquals(new BigDecimal("97"), cap.getValue().getStopPrice());
    assertEquals("海龟,练习1", cap.getValue().getTags());
  }

  @Test
  void update_relaxesStopLoss_throws() {
    var existing = new JournalTrade();
    existing.setId(1L);
    existing.setEntryPrice(new BigDecimal("100"));
    existing.setStopPrice(new BigDecimal("95"));
    existing.setInitialRisk(new BigDecimal("5.00"));
    existing.setEntryShares(100);
    existing.setIsOpen(1);
    when(repo.findActiveById(1L)).thenReturn(Optional.of(existing));

    var req = new JournalTradeUpdateRequest();
    req.setStopPrice(new BigDecimal("93")); // widen — violation

    var ex = assertThrows(IllegalArgumentException.class, () -> service.update(1L, req));
    assertTrue(ex.getMessage().contains("止损"));
    verify(repo, never()).save(any());
  }
}
