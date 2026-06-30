package com.quant.controller;

import com.quant.dto.journal.JournalTradeDTO;
import com.quant.repository.UserRepository;
import com.quant.security.JwtTokenProvider;
import com.quant.service.journal.JournalService;
import com.quant.service.journal.JournalStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JournalController.class)
@AutoConfigureMockMvc(addFilters = false)
class JournalControllerTest {

    @Autowired MockMvc mvc;
    @MockBean JournalService service;
    @MockBean JournalStatsService stats;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean UserRepository userRepository;

    @Test
    @WithMockUser
    void post_trade_returnsCreated() throws Exception {
        var dto = JournalTradeDTO.builder().id(1L).stockCode("600519").build();
        when(service.create(any(), any())).thenReturn(dto);

        mvc.perform(post("/api/journal/trades")
                .contentType("application/json")
                .content("""
                    {"mode":"REAL","stockCode":"600519","entryPrice":100,
                     "stopPrice":95,"targetPrice":115,"entryShares":100}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @WithMockUser
    void list_returnsPage() throws Exception {
        when(service.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/api/journal/trades"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void delete_trade_returnsNoContent() throws Exception {
        mvc.perform(delete("/api/journal/trades/1"))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser
    void put_trade_returnsUpdated() throws Exception {
        var dto = JournalTradeDTO.builder().id(1L).stockCode("600519").build();
        when(service.update(any(Long.class), any())).thenReturn(dto);

        mvc.perform(put("/api/journal/trades/1")
                .contentType("application/json")
                .content("{\"exitPrice\":115,\"exitReason\":\"manual\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @WithMockUser
    void get_trade_returnsOne() throws Exception {
        var dto = JournalTradeDTO.builder().id(1L).stockCode("600519").build();
        when(service.findOne(1L)).thenReturn(dto);

        mvc.perform(get("/api/journal/trades/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stockCode").value("600519"));
    }

    @Test
    @WithMockUser
    void get_openList_returnsList() throws Exception {
        when(service.listOpen()).thenReturn(java.util.List.of(
                JournalTradeDTO.builder().id(1L).stockCode("600519").build()));

        mvc.perform(get("/api/journal/trades/open"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1));
    }
}
