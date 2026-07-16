package com.quant.service.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.dto.journal.JournalTradeCreateRequest;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;

@ExtendWith(MockitoExtension.class)
class JournalServiceCreateTest {

  @Mock JournalTradeRepository repo;
  @InjectMocks JournalService service;

  @Test
  void create_validatesRiskRewardRatio() {
    var req = new JournalTradeCreateRequest();
    req.setMode("REAL");
    req.setStockCode("600519");
    req.setEntryPrice(new BigDecimal("100"));
    req.setStopPrice(new BigDecimal("98"));
    req.setTargetPrice(new BigDecimal("101")); // R:R = 1:0.5 (too low)
    req.setEntryShares(100);

    var ex = assertThrows(IllegalArgumentException.class, () -> service.create(req, "user-1"));
    assertTrue(ex.getMessage().contains("1:3"));
    verifyNoInteractions(repo);
  }

  @Test
  void create_validatesRiskPercent() {
    var req = new JournalTradeCreateRequest();
    req.setMode("REAL");
    req.setStockCode("600519");
    req.setEntryPrice(new BigDecimal("100"));
    req.setStopPrice(new BigDecimal("95"));
    req.setTargetPrice(new BigDecimal("115"));
    req.setEntryShares(100);
    req.setRiskPercent(new BigDecimal("0.05")); // 5% > 2% hard limit

    var ex = assertThrows(IllegalArgumentException.class, () -> service.create(req, "user-1"));
    assertTrue(ex.getMessage().contains("2%"));
    verifyNoInteractions(repo);
  }

  @Test
  void create_computesInitialRiskAndPersists() {
    when(repo.save(any(JournalTrade.class)))
        .thenAnswer(
            inv -> {
              JournalTrade j = inv.getArgument(0);
              j.setId(42L);
              return j;
            });

    var req = new JournalTradeCreateRequest();
    req.setMode("PAPER");
    req.setStockCode("002415");
    req.setStockName("海康");
    req.setEntryPrice(new BigDecimal("35"));
    req.setStopPrice(new BigDecimal("33"));
    req.setTargetPrice(new BigDecimal("41")); // R:R = 6:2 = 3:1, OK
    req.setEntryShares(500);
    req.setTags("海龟,练习1");
    req.setSetupNotes("突破前高");

    var dto = service.create(req, "user-1");

    ArgumentCaptor<JournalTrade> cap = ArgumentCaptor.forClass(JournalTrade.class);
    verify(repo).save(cap.capture());
    var saved = cap.getValue();

    // Auto-calculated
    assertEquals(new BigDecimal("2.00"), saved.getInitialRisk()); // 35 - 33
    assertEquals(1, saved.getIsOpen()); // open by default
    assertEquals("MANUAL", saved.getSource()); // manual creation
    assertEquals("user-1", saved.getCreatedBy()); // username captured
    assertNotNull(saved.getEntryDate()); // defaulted to now()

    // Direct copy from request
    assertEquals(JournalTrade.Mode.PAPER, saved.getMode());
    assertEquals("002415", saved.getStockCode());
    assertEquals("海康", saved.getStockName());
    assertEquals(0, new BigDecimal("35").compareTo(saved.getEntryPrice()));
    assertEquals(0, new BigDecimal("33").compareTo(saved.getStopPrice()));
    assertEquals(0, new BigDecimal("41").compareTo(saved.getTargetPrice()));
    assertEquals(500, saved.getEntryShares());
    assertEquals("海龟,练习1", saved.getTags());
    assertEquals("突破前高", saved.getSetupNotes());

    // DTO returned matches saved
    assertEquals(dto.getInitialRisk(), saved.getInitialRisk());
  }
}
