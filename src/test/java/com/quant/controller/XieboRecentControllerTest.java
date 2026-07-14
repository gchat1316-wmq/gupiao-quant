package com.quant.controller;

import com.quant.dto.xiebo.RecentNoteDto;
import com.quant.dto.xiebo.RecentWatchDto;
import com.quant.repository.UserRepository;
import com.quant.security.JwtTokenProvider;
import com.quant.service.XieboRecentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(XieboRecentController.class)
@AutoConfigureMockMvc(addFilters = false)
class XieboRecentControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    XieboRecentService service;

    @MockBean
    JwtTokenProvider jwtTokenProvider;

    @MockBean
    UserRepository userRepository;

    @Test
    void list_noAuth_returns200() throws Exception {
        RecentWatchDto dto = RecentWatchDto.builder()
                .stockCode("600519").stockName("贵州茅台").type("质量优选")
                .currentPrice(new BigDecimal("1893.20")).hasNote(false).build();
        when(service.listAll(null)).thenReturn(List.of(dto));

        mvc.perform(get("/api/xiebo/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stockCode").value("600519"))
                .andExpect(jsonPath("$[0].stockName").value("贵州茅台"))
                .andExpect(jsonPath("$[0].currentPrice").value(1893.20));
    }

    @Test
    void list_withTypeParam_passesTypeToService() throws Exception {
        when(service.listAll("科技AI")).thenReturn(List.of());

        mvc.perform(get("/api/xiebo/recent").param("type", "科技AI"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(service).listAll("科技AI");
    }

    @Test
    void note_returnsDto() throws Exception {
        RecentNoteDto dto = RecentNoteDto.builder()
                .stockCode("600519").noteHtml("<p>好</p>").build();
        when(service.getNote("600519")).thenReturn(dto);

        mvc.perform(get("/api/xiebo/recent/600519/note"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("600519"))
                .andExpect(jsonPath("$.noteHtml").value("<p>好</p>"));
    }

    @Test
    void note_missing_returnsNull() throws Exception {
        when(service.getNote("X")).thenReturn(null);

        mvc.perform(get("/api/xiebo/recent/X/note"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }
}
