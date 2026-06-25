package com.quant.service;

import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockQueryService.resolveStock XD/除权除息简称兼容")
class StockQueryServiceTest {

    @Mock TradeStockBasicRepository stockBasicRepository;
    @Mock TradeStockFinancialRepository financialRepository;
    @Mock AStockDataQuoteService aStockDataQuoteService;
    @Mock org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private StockQueryService service;

    @BeforeEach
    void setUp() {
        // 默认所有股票实时行情为空，迫使最新市值走 null 兜底
        org.mockito.Mockito.lenient().when(aStockDataQuoteService.fetchQuotes(any())).thenReturn(java.util.Map.of());
        service = new StockQueryService(stockBasicRepository, financialRepository, aStockDataQuoteService, jdbcTemplate);
    }

    private TradeStockBasic basic(String code, String name) {
        TradeStockBasic b = new TradeStockBasic();
        b.setStockCode(code);
        b.setStockName(name);
        return b;
    }

    private TradeStockFinancial financial(String code, String name) {
        TradeStockFinancial f = new TradeStockFinancial();
        f.setStockCode(code);
        f.setStockName(name);
        f.setReportDate(LocalDate.of(2025, 9, 30));
        f.setRevenue(BigDecimal.valueOf(1_000_000_000L));
        return f;
    }

    @Test
    @DisplayName("用户输入全名「兆易创新」，DB 存 XD 截断简称「XD兆易创」→ 应命中")
    void resolvesXdTruncatedName_viaFullNameInput() {
        TradeStockBasic dbRow = basic("603986.SH", "XD兆易创");
        when(stockBasicRepository.findByStockNameLike("兆易创新")).thenReturn(List.of(dbRow));

        Optional<TradeStockBasic> result = service.resolveStock("兆易创新");

        assertThat(result).isPresent();
        assertThat(result.get().getStockCode()).isEqualTo("603986.SH");
        assertThat(result.get().getStockName()).isEqualTo("XD兆易创");
    }

    @Test
    @DisplayName("用户输入 XR 截断简称「XR伟明环」，DB 存全名「伟明环保」→ 通过 strip 兜底命中")
    void resolvesXrTruncatedName_viaStripFallback() {
        TradeStockBasic dbRow = basic("603568.SH", "伟明环保");
        when(stockBasicRepository.findByStockNameLike("XR伟明环")).thenReturn(List.of());
        when(stockBasicRepository.findByStockNameLike("伟明环")).thenReturn(List.of(dbRow));

        Optional<TradeStockBasic> result = service.resolveStock("XR伟明环");

        assertThat(result).isPresent();
        assertThat(result.get().getStockCode()).isEqualTo("603568.SH");
        assertThat(result.get().getStockName()).isEqualTo("伟明环保");
    }

    @Test
    @DisplayName("用户输入 DR 截断简称「DR东方电」，DB 存全名「东方电气」→ 通过 strip 兜底命中")
    void resolvesDrTruncatedName_viaStripFallback() {
        TradeStockBasic dbRow = basic("603606.SH", "东方电气");
        when(stockBasicRepository.findByStockNameLike("DR东方电")).thenReturn(List.of());
        when(stockBasicRepository.findByStockNameLike("东方电")).thenReturn(List.of(dbRow));

        Optional<TradeStockBasic> result = service.resolveStock("DR东方电");

        assertThat(result).isPresent();
        assertThat(result.get().getStockCode()).isEqualTo("603606.SH");
        assertThat(result.get().getStockName()).isEqualTo("东方电气");
    }

    @Test
    @DisplayName("用户输入「XD兆易创」全命中 XD 期 DB → 走原始 LIKE 路径")
    void resolvesXdName_viaOriginalLike() {
        TradeStockBasic dbRow = basic("603986.SH", "XD兆易创");
        when(stockBasicRepository.findByStockNameLike("XD兆易创")).thenReturn(List.of(dbRow));

        Optional<TradeStockBasic> result = service.resolveStock("XD兆易创");

        assertThat(result).isPresent();
        assertThat(result.get().getStockCode()).isEqualTo("603986.SH");
    }

    @Test
    @DisplayName("用户输入「兆易创新」，basic 表无命中，从 financial 表兜底命中")
    void fallsBackToFinancialTable_onXdTruncatedName() {
        TradeStockFinancial finRow = financial("603986.SH", "XD兆易创");
        when(stockBasicRepository.findByStockNameLike("兆易创新")).thenReturn(List.of());
        when(financialRepository.findByStockNameLike("兆易创新")).thenReturn(List.of(finRow));

        Optional<TradeStockBasic> result = service.resolveStock("兆易创新");

        assertThat(result).isPresent();
        assertThat(result.get().getStockCode()).isEqualTo("603986.SH");
        assertThat(result.get().getStockName()).isEqualTo("XD兆易创");
    }

    @Test
    @DisplayName("stripXdPrefix 应正确处理 XD/XR/DR/N/*ST/ST 前缀")
    void stripXdPrefix_handlesAllPrefixes() {
        assertThat(StockQueryService.stripXdPrefix("XD兆易创")).isEqualTo("兆易创");
        assertThat(StockQueryService.stripXdPrefix("XR伟明环")).isEqualTo("伟明环");
        assertThat(StockQueryService.stripXdPrefix("DR东方电")).isEqualTo("东方电");
        assertThat(StockQueryService.stripXdPrefix("N北方华创")).isEqualTo("北方华创");
        assertThat(StockQueryService.stripXdPrefix("*ST天龙")).isEqualTo("天龙");
        assertThat(StockQueryService.stripXdPrefix("ST天龙")).isEqualTo("天龙");
        // 不应误伤
        assertThat(StockQueryService.stripXdPrefix("兆易创新")).isEqualTo("兆易创新");
        assertThat(StockQueryService.stripXdPrefix("宁德时代")).isEqualTo("宁德时代");
    }

    @Test
    @DisplayName("用户输入带前缀的简称在 basic 和 financial 都查不到时 → 返回 empty")
    void returnsEmpty_whenNoMatch() {
        when(stockBasicRepository.findByStockNameLike(anyString())).thenReturn(List.of());
        when(financialRepository.findByStockNameLike(anyString())).thenReturn(List.of());

        Optional<TradeStockBasic> result = service.resolveStock("不存在的股票");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("strip 后只剩 1 字（如输入「X」），不应触发兜底查询，避免误匹配")
    void doesNotTriggerStripFallback_whenStrippedTooShort() {
        // "X" 本身长度 1，stripXD 前缀也不匹配（没有 XA/XB/XC 这种），stripped 还是 "X"
        when(stockBasicRepository.findByStockNameLike(eq("X"))).thenReturn(List.of());
        when(financialRepository.findByStockNameLike(eq("X"))).thenReturn(List.of());

        Optional<TradeStockBasic> result = service.resolveStock("X");

        assertThat(result).isEmpty();
    }
}