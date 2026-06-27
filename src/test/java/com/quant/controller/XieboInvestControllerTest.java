package com.quant.controller;

import com.quant.dto.xieboinvest.XieboWatchlistItemDTO;
import com.quant.dto.xieboinvest.XieboAnalysisDetailDTO;
import com.quant.dto.xieboinvest.XieboAnalysisListItemDTO;
import com.quant.dto.xieboinvest.XieboNewsDTO;
import com.quant.dto.xieboinvest.XieboQuoteDTO;
import com.quant.service.xieboinvest.XieboInvestAnalysisService;
import com.quant.service.xieboinvest.XieboInvestNewsService;
import com.quant.service.xieboinvest.XieboInvestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(XieboInvestController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("XieboInvestController")
class XieboInvestControllerTest {

    @Autowired MockMvc mvc;

    @MockBean XieboInvestService service;
    @MockBean XieboInvestAnalysisService analysisService;
    @MockBean XieboInvestNewsService newsService;

    @Test
    @DisplayName("watchlist endpoint returns persisted monitoring rows")
    void watchlistEndpointReturnsRows() throws Exception {
        XieboWatchlistItemDTO item = XieboWatchlistItemDTO.builder()
                .stockCode("600519.SH")
                .stockName("贵州茅台")
                .peg(new BigDecimal("0.98"))
                .pegRating("低估")
                .build();
        when(service.getWatchlist()).thenReturn(List.of(item));

        mvc.perform(get("/api/xiebo-invest/watchlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stockCode").value("600519.SH"))
                .andExpect(jsonPath("$[0].pegRating").value("低估"));
    }

    @Test
    @DisplayName("quote endpoint returns single stock peg snapshot")
    void quoteEndpointReturnsSnapshot() throws Exception {
        XieboQuoteDTO quote = XieboQuoteDTO.builder()
                .stockCode("002371.SZ")
                .stockName("北方华创")
                .peg(new BigDecimal("1.26"))
                .pegRating("合理")
                .build();
        when(service.getQuote("北方华创")).thenReturn(quote);

        mvc.perform(get("/api/xiebo-invest/quote").param("keyword", "北方华创"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("002371.SZ"))
                .andExpect(jsonPath("$.pegRating").value("合理"));
    }

    @Test
    @DisplayName("sector-pe endpoint returns aggregate statistics")
    void sectorEndpointReturnsAggregateStats() throws Exception {
        when(service.getSectorPe("北方华创")).thenReturn(Map.of(
                "sectorName", "半导体设备",
                "count", 2,
                "avgPe", new BigDecimal("48.50"),
                "medianPe", new BigDecimal("48.50"),
                "stocks", List.of(Map.of("stockCode", "002371.SZ"))
        ));

        mvc.perform(get("/api/xiebo-invest/sector-pe").param("keyword", "北方华创"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectorName").value("半导体设备"))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    @DisplayName("watchlist add and delete endpoints delegate to service")
    void watchlistMutationsDelegateToService() throws Exception {
        XieboWatchlistItemDTO item = XieboWatchlistItemDTO.builder()
                .stockCode("002371.SZ")
                .stockName("北方华创")
                .build();
        when(service.addWatchlist("北方华创")).thenReturn(List.of(item));

        mvc.perform(post("/api/xiebo-invest/watchlist")
                        .contentType("application/json")
                        .content("{\"keyword\":\"北方华创\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stockCode").value("002371.SZ"));

        mvc.perform(delete("/api/xiebo-invest/watchlist/002371.SZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("removed"));
    }

    @Test
    @DisplayName("news endpoint returns aggregated news columns")
    void newsEndpointReturnsAggregatedColumns() throws Exception {
        XieboNewsDTO news = XieboNewsDTO.builder()
                .collectedAt("2026-06-17T12:00:00")
                .stockNews(List.of(XieboNewsDTO.NewsItemDTO.builder().title("个股新闻A").build()))
                .announcements(List.of(XieboNewsDTO.NewsItemDTO.builder().title("公司公告A").build()))
                .marketNews(List.of(XieboNewsDTO.NewsItemDTO.builder().title("市场快讯A").build()))
                .build();
        when(newsService.load("002028")).thenReturn(news);

        mvc.perform(get("/api/xiebo-invest/news").param("keyword", "002028"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockNews[0].title").value("个股新闻A"))
                .andExpect(jsonPath("$.announcements[0].title").value("公司公告A"))
                .andExpect(jsonPath("$.marketNews[0].title").value("市场快讯A"));
    }

    @Test
    @DisplayName("analysis endpoints create list and fetch detail")
    void analysisEndpointsCreateListAndDetail() throws Exception {
        XieboAnalysisDetailDTO detail = XieboAnalysisDetailDTO.builder()
                .id(1L)
                .stockCode("002371.SZ")
                .stockName("北方华创")
                .status("completed")
                .reportMarkdown("# 北方华创 PEG 估值分析")
                .build();
        XieboAnalysisListItemDTO listItem = XieboAnalysisListItemDTO.builder()
                .id(1L)
                .stockCode("002371.SZ")
                .stockName("北方华创")
                .status("completed")
                .conclusion("PEG 合理")
                .build();
        when(analysisService.create("北方华创")).thenReturn(detail);
        when(analysisService.list()).thenReturn(List.of(listItem));
        when(analysisService.detail(1L)).thenReturn(detail);

        mvc.perform(post("/api/xiebo-invest/analysis")
                        .contentType("application/json")
                        .content("{\"keyword\":\"北方华创\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("completed"));

        mvc.perform(get("/api/xiebo-invest/analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stockCode").value("002371.SZ"));

        mvc.perform(get("/api/xiebo-invest/analysis/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportMarkdown").value("# 北方华创 PEG 估值分析"));
    }
}
