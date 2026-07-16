package com.quant.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.quant.entity.InvestXieboRecentWatch;
import com.quant.entity.InvestXieboStockNote;
import com.quant.repository.InvestXieboRecentWatchRepository;
import com.quant.repository.InvestXieboStockNoteRepository;
import com.quant.repository.UserRepository;
import com.quant.security.JwtTokenProvider;
import com.quant.service.AStockDataQuoteService;

@WebMvcTest(AdminXieboRecentController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminXieboRecentControllerTest {

  @Autowired MockMvc mvc;
  @MockBean InvestXieboRecentWatchRepository watchRepo;
  @MockBean InvestXieboStockNoteRepository noteRepo;
  @MockBean UserRepository userRepository;
  @MockBean JwtTokenProvider jwtTokenProvider;
  @MockBean AStockDataQuoteService quoteService;

  @Test
  void create_newStock_returns200() throws Exception {
    when(watchRepo.existsById("600519")).thenReturn(false);
    when(watchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    mvc.perform(
            post("/api/admin/xiebo/recent")
                .contentType("application/json")
                .content("{\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"type\":\"质量优选\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true))
        .andExpect(jsonPath("$.stockCode").value("600519"));
  }

  @Test
  void create_duplicate_returns400() throws Exception {
    when(watchRepo.existsById("600519")).thenReturn(true);

    mvc.perform(
            post("/api/admin/xiebo/recent")
                .contentType("application/json")
                .content("{\"stockCode\":\"600519\",\"stockName\":\"贵州茅台\",\"type\":\"质量优选\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("DUPLICATE"));
  }

  @Test
  void update_existingStock_returns200() throws Exception {
    InvestXieboRecentWatch w = new InvestXieboRecentWatch();
    w.setStockCode("600519");
    when(watchRepo.findById("600519")).thenReturn(Optional.of(w));
    when(watchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    mvc.perform(
            put("/api/admin/xiebo/recent/600519")
                .contentType("application/json")
                .content("{\"stockCode\":\"600519\",\"stockName\":\"茅台\",\"type\":\"质量优选\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
  }

  @Test
  void delete_existingStock_returns200() throws Exception {
    mvc.perform(delete("/api/admin/xiebo/recent/600519"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
    verify(watchRepo).deleteById("600519");
  }

  @Test
  void upsertNote_sanitizesHtml() throws Exception {
    InvestXieboStockNote note = new InvestXieboStockNote();
    note.setStockCode("600519");
    when(noteRepo.findById("600519")).thenReturn(Optional.of(note));
    when(noteRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    mvc.perform(
            put("/api/admin/xiebo/recent/600519/note")
                .contentType("application/json")
                .content("{\"noteHtml\":\"<p>好公司<script>alert(1)</script></p>\"}"))
        .andExpect(status().isOk());

    verify(noteRepo)
        .save(argThat(n -> n.getNoteHtml() != null && !n.getNoteHtml().contains("<script>")));
  }
}
