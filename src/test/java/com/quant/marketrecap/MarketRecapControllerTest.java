package com.quant.marketrecap;

import com.quant.controller.MarketRecapController;
import com.quant.dto.marketrecap.KeyDataItemDTO;
import com.quant.dto.marketrecap.MarketRecapDetailDTO;
import com.quant.dto.marketrecap.MarketRecapPageDTO;
import com.quant.dto.marketrecap.MarketRecapSummaryDTO;
import com.quant.dto.marketrecap.SectorCardDTO;
import com.quant.dto.marketrecap.StrategyItemDTO;
import com.quant.service.MarketRecapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MarketRecapController.class)
@DisplayName("MarketRecapController")
class MarketRecapControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    MarketRecapService marketRecapService;

    @Test
    @DisplayName("市场列表接口返回数组")
    void returnsMarketList() throws Exception {
        when(marketRecapService.listMarkets()).thenReturn(List.of("A股", "港股"));

        mvc.perform(get("/api/market-recaps/markets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("A股"))
                .andExpect(jsonPath("$[1]").value("港股"));
    }

    @Test
    @DisplayName("页面接口返回 markets selectedMarket latest timeline")
    void returnsPageStructure() throws Exception {
        when(marketRecapService.getPage("A股")).thenReturn(samplePage());

        mvc.perform(get("/api/market-recaps").param("market", "A股"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedMarket").value("A股"))
                .andExpect(jsonPath("$.markets[0]").value("A股"))
                .andExpect(jsonPath("$.latest.title").value("A股复盘"))
                .andExpect(jsonPath("$.timeline.length()").value(1));
    }

    @Test
    @DisplayName("详情接口返回结构卡片和正文 HTML")
    void returnsDetail() throws Exception {
        when(marketRecapService.getDetail(7L)).thenReturn(sampleDetail());

        mvc.perform(get("/api/market-recaps/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("A股复盘"))
                .andExpect(jsonPath("$.sectors[0].name").value("半导体"))
                .andExpect(jsonPath("$.keyData[0].label").value("涨停溢价"))
                .andExpect(jsonPath("$.nextDayStrategy[0].label").value("持仓者"))
                .andExpect(jsonPath("$.contentHtml").value("<h1>正文</h1>"));
    }

    @Test
    @DisplayName("不存在的详情返回 404")
    void returns404ForMissingDetail() throws Exception {
        when(marketRecapService.getDetail(999L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到复盘：999"));

        mvc.perform(get("/api/market-recaps/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("badge 接口返回 today/yesterday/latestId 计数")
    void returnsBadgeSummary() throws Exception {
        when(marketRecapService.getBadgeSummary()).thenReturn(
                com.quant.dto.marketrecap.MarketRecapBadgeDTO.builder()
                        .today(1)
                        .yesterday(2)
                        .latestId(7L)
                        .latestTradeDate("2026-06-17")
                        .build()
        );

        mvc.perform(get("/api/market-recaps/badge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(1))
                .andExpect(jsonPath("$.yesterday").value(2))
                .andExpect(jsonPath("$.latestId").value(7))
                .andExpect(jsonPath("$.latestTradeDate").value("2026-06-17"));
    }

    @Test
    @DisplayName("badge 接口无数据时返回全零 + null latestId")
    void returnsEmptyBadge() throws Exception {
        when(marketRecapService.getBadgeSummary()).thenReturn(
                com.quant.dto.marketrecap.MarketRecapBadgeDTO.builder()
                        .today(0)
                        .yesterday(0)
                        .latestId(null)
                        .latestTradeDate(null)
                        .build()
        );

        mvc.perform(get("/api/market-recaps/badge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(0))
                .andExpect(jsonPath("$.yesterday").value(0))
                .andExpect(jsonPath("$.latestId").isEmpty());
    }

    private MarketRecapPageDTO samplePage() {
        return MarketRecapPageDTO.builder()
                .markets(List.of("A股", "港股"))
                .selectedMarket("A股")
                .latest(sampleDetail())
                .timeline(List.of(MarketRecapSummaryDTO.builder()
                        .id(7L)
                        .market("A股")
                        .tradeDate("2026-06-09")
                        .title("A股复盘")
                        .indexesSummary("上证+1.28%")
                        .advanceDecline("1.62:1")
                        .limitUp(210)
                        .limitDown(15)
                        .sentiment("启动期")
                        .summaryExcerpt("科技暴力反弹")
                        .build()))
                .build();
    }

    private MarketRecapDetailDTO sampleDetail() {
        return MarketRecapDetailDTO.builder()
                .id(7L)
                .market("A股")
                .tradeDate("2026-06-09")
                .title("A股复盘")
                .indexesSummary("上证+1.28%")
                .advanceDecline("1.62:1")
                .limitUp(210)
                .limitDown(15)
                .sentiment("启动期")
                .summaryExcerpt("科技暴力反弹")
                .sectors(List.of(SectorCardDTO.builder()
                        .name("半导体")
                        .strengthLabel("4+")
                        .leaders(List.of("沪硅产业"))
                        .catalyst("费城半导体+5.61%")
                        .build()))
                .risks(List.of("接力不足"))
                .catalysts(List.of("美股半导体大涨"))
                .keyData(List.of(KeyDataItemDTO.builder().label("涨停溢价").value("1.97%").build()))
                .nextDayStrategy(List.of(StrategyItemDTO.builder().label("持仓者").value("持有").build()))
                .contentHtml("<h1>正文</h1>")
                .build();
    }
}
