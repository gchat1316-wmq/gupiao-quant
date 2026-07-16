package com.quant.service.journal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.entity.InvestPositionFill;
import com.quant.entity.JournalTrade;
import com.quant.repository.InvestPositionFillRepository;
import com.quant.repository.JournalTradeRepository;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class JournalServiceListTest {

  @Mock JournalTradeRepository repo;
  @Mock InvestPositionFillRepository fillRepo;
  @InjectMocks JournalService service;

  @Test
  void list_passesFiltersToRepo() {
    when(repo.findAll(
            any(org.springframework.data.jpa.domain.Specification.class),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(org.springframework.data.domain.Page.empty());
    var page =
        service.list(
            "PAPER", true, null, null, null, org.springframework.data.domain.PageRequest.of(0, 20));
    assertNotNull(page);
    verify(repo)
        .findAll(
            any(org.springframework.data.jpa.domain.Specification.class),
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

  @Test
  void pendingFills_excludesOpenAndAddActions() {
    var openFill = new InvestPositionFill();
    openFill.setId(11L);
    openFill.setAction("open");
    openFill.setPrice(new BigDecimal("100"));
    openFill.setLots(new BigDecimal("1"));
    openFill.setFilledAt(LocalDateTime.now());

    var addFill = new InvestPositionFill();
    addFill.setId(12L);
    addFill.setAction("add");
    addFill.setPrice(new BigDecimal("100"));
    addFill.setLots(new BigDecimal("1"));
    addFill.setFilledAt(LocalDateTime.now());

    var clearFill = new InvestPositionFill();
    clearFill.setId(13L);
    clearFill.setAction("clear");
    clearFill.setPrice(new BigDecimal("110"));
    clearFill.setLots(new BigDecimal("1"));
    clearFill.setFilledAt(LocalDateTime.now());

    when(fillRepo.findRecentSince(any(LocalDateTime.class)))
        .thenReturn(List.of(openFill, addFill, clearFill));
    when(repo.findBySourceRef(any())).thenReturn(Optional.empty());

    var out = service.pendingFills();
    assertEquals(1, out.size());
    assertEquals(13L, out.get(0).getFillId());
  }

  @Test
  void syncFromFill_missingFill_throwsIAE() {
    when(fillRepo.findById(999L)).thenReturn(Optional.empty());
    var ex =
        assertThrows(IllegalArgumentException.class, () -> service.syncFromFill(999L, "user-1"));
    assertTrue(ex.getMessage().contains("999"));
    verify(repo, never()).save(any());
  }

  @Test
  void syncFromFill_duplicateSync_throwsISE() {
    var existing = new InvestPositionFill();
    existing.setId(20L);
    existing.setAction("clear");
    existing.setPrice(new BigDecimal("100"));
    existing.setLots(new BigDecimal("1"));
    existing.setFilledAt(LocalDateTime.now());
    when(fillRepo.findById(20L)).thenReturn(Optional.of(existing));
    when(repo.findBySourceRef(20L)).thenReturn(Optional.of(new JournalTrade()));

    var ex = assertThrows(IllegalStateException.class, () -> service.syncFromFill(20L, "user-1"));
    assertTrue(ex.getMessage().contains("已同步"));
    verify(repo, never()).save(any());
  }

  @Test
  void syncFromFill_clearAction_autoCloses() {
    var fill = new InvestPositionFill();
    fill.setId(30L);
    fill.setAction("clear");
    fill.setPrice(new BigDecimal("115"));
    fill.setLots(new BigDecimal("2"));
    fill.setFilledAt(LocalDateTime.of(2026, 6, 30, 15, 0));
    when(fillRepo.findById(30L)).thenReturn(Optional.of(fill));
    when(repo.findBySourceRef(30L)).thenReturn(Optional.empty());
    when(repo.save(any(JournalTrade.class)))
        .thenAnswer(
            inv -> {
              JournalTrade j = inv.getArgument(0);
              j.setId(99L);
              return j;
            });

    var dto = service.syncFromFill(30L, "user-1");

    // verify returned DTO reflects the auto-close
    assertNotNull(dto);
    assertEquals(99L, dto.getId());
    assertEquals("POOL_SYNC", dto.getSource());
    assertEquals(30L, dto.getSourceRefId());
    assertFalse(dto.getIsOpen());

    ArgumentCaptor<JournalTrade> cap = ArgumentCaptor.forClass(JournalTrade.class);
    verify(repo).save(cap.capture());
    var saved = cap.getValue();

    assertEquals(0, saved.getIsOpen());
    assertEquals("POOL_SYNC", saved.getSource());
    assertEquals(30L, saved.getSourceRefId());
    assertEquals(0, saved.getPnlAmount().compareTo(new BigDecimal("0.00")));
    assertEquals(0, saved.getRMultiple().compareTo(new BigDecimal("0.0000")));
  }
}
