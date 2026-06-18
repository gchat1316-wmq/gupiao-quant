package com.quant.invest;

import com.quant.dto.invest.PoolItemDTO;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.AStockDataQuoteService;
import com.quant.service.InvestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvestService - 股票池列表")
class InvestServicePoolTest {

    @Mock TradeStockBasicRepository stockBasicRepo;
    @Mock TradeStockFinancialRepository financialRepo;
    @Mock InvestStockPoolRepository poolRepo;
    @Mock AStockDataQuoteService quoteService;

    InvestService service;

    @BeforeEach
    void setUp() {
        service = new InvestService(stockBasicRepo, financialRepo, poolRepo, quoteService);
    }

    @Test
    @DisplayName("基础表缺失时使用股票池自身的公司名称")
    void listPoolUsesPoolStockNameWhenBasicMissing() {
        InvestStockPool pool = new InvestStockPool();
        pool.setId(14);
        pool.setStockCode("688296");
        pool.setStockName("金海通");
        pool.setPoolType("tech_vc");

        when(poolRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pool));
        when(stockBasicRepo.findByStockCodeIn(org.mockito.ArgumentMatchers.argThat(codes ->
                codes.contains("688296")))).thenReturn(Collections.emptyList());
        when(financialRepo.findLatestByStockCodes(List.of("688296"))).thenReturn(Collections.emptyList());
        when(quoteService.fetchQuotes(List.of("688296"))).thenReturn(Collections.emptyMap());
        when(quoteService.fetchYearStartCloses(org.mockito.ArgumentMatchers.eq(List.of("688296")),
                org.mockito.ArgumentMatchers.any())).thenReturn(Collections.emptyMap());

        List<PoolItemDTO> result = service.listPool();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockName()).isEqualTo("金海通");
    }

    @Test
    @DisplayName("基础表代码后缀大小写不一致时仍显示公司名称")
    void listPoolMatchesBasicCodeCaseInsensitive() {
        InvestStockPool pool = new InvestStockPool();
        pool.setId(24);
        pool.setStockCode("688525.sh");
        pool.setPoolType("tech_ai");

        TradeStockBasic basic = new TradeStockBasic();
        basic.setStockCode("688525.SH");
        basic.setStockName("佰维存储");

        when(poolRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pool));
        when(stockBasicRepo.findByStockCodeIn(org.mockito.ArgumentMatchers.argThat(codes ->
                codes.contains("688525.sh") && codes.contains("688525.SH")))).thenReturn(List.of(basic));
        when(financialRepo.findLatestByStockCodes(List.of("688525.sh"))).thenReturn(Collections.emptyList());
        when(quoteService.fetchQuotes(List.of("688525.sh"))).thenReturn(Collections.emptyMap());
        when(quoteService.fetchYearStartCloses(org.mockito.ArgumentMatchers.eq(List.of("688525.sh")),
                org.mockito.ArgumentMatchers.any())).thenReturn(Collections.emptyMap());

        List<PoolItemDTO> result = service.listPool();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockName()).isEqualTo("佰维存储");
    }

    @Test
    @DisplayName("科技风投股票池按截图 displayOrder 排序且保留其它池")
    void listPoolOrdersTechVcByDisplayOrder() {
        InvestStockPool second = pool(2, "688515.SH", "裕太微", "tech_vc", 20);
        InvestStockPool quality = pool(3, "600519.SH", "贵州茅台", "quality", null);
        InvestStockPool first = pool(1, "688610.SH", "埃科光电", "tech_vc", 10);

        when(poolRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(second, quality, first));
        when(stockBasicRepo.findByStockCodeIn(any())).thenReturn(Collections.emptyList());
        when(financialRepo.findLatestByStockCodes(List.of("688515.SH", "600519.SH", "688610.SH"))).thenReturn(Collections.emptyList());
        when(quoteService.fetchQuotes(List.of("688515.SH", "600519.SH", "688610.SH"))).thenReturn(Collections.emptyMap());
        when(quoteService.fetchYearStartCloses(eq(List.of("688515.SH", "600519.SH", "688610.SH")), any()))
                .thenReturn(Collections.emptyMap());

        List<PoolItemDTO> result = service.listPool();

        assertThat(result).extracting(PoolItemDTO::getStockName)
                .containsExactly("埃科光电", "裕太微", "贵州茅台");
    }

    @Test
    @DisplayName("当前市值、今年涨幅和估值情况都按实时数据派生")
    void listPoolPrefersDerivedMarketCapBeforePersistedSnapshot() {
        InvestStockPool pool = pool(1, "688610.SH", "埃科光电", "tech_vc", 10);
        pool.setCurrentMarketCap(new BigDecimal("144.70"));
        pool.setYtdGainPct(new BigDecimal("244.28"));
        pool.setValuationRange("泡沫");
        pool.setRevenueForecastY1(new BigDecimal("10.15"));
        pool.setRevenueForecastY2(new BigDecimal("14.08"));

        TradeStockBasic basic = new TradeStockBasic();
        basic.setStockCode("688610.SH");
        basic.setStockName("埃科光电");
        basic.setTotalShares(68_000_000L);

        AStockDataQuoteService.QuoteSnapshot quote = new AStockDataQuoteService.QuoteSnapshot(
                "688610.SH",
                new BigDecimal("239.99"),
                new BigDecimal("239.70"),
                new BigDecimal("163.19"),
                null,
                "a-stock-data/tencent"
        );

        when(poolRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pool));
        when(stockBasicRepo.findByStockCodeIn(any())).thenReturn(List.of(basic));
        when(financialRepo.findLatestByStockCodes(List.of("688610.SH"))).thenReturn(Collections.emptyList());
        when(quoteService.fetchQuotes(List.of("688610.SH"))).thenReturn(java.util.Map.of("688610.SH", quote));
        when(quoteService.fetchYearStartCloses(eq(List.of("688610.SH")), any()))
                .thenReturn(java.util.Map.of("688610.SH", new BigDecimal("70.00")));

        PoolItemDTO item = service.listPool().get(0);

        assertThat(item.getLatestPrice()).isEqualByComparingTo("239.99");
        assertThat(item.getCurrentMarketCap()).isEqualByComparingTo("163.19");
        assertThat(item.getMarketCap()).isEqualByComparingTo("163.19");
        assertThat(item.getYtdGainPct()).isEqualByComparingTo("242.84");
        assertThat(item.getYtdGain()).isEqualByComparingTo("242.84");
        assertThat(item.getValuationRange()).isEqualTo("泡沫");
    }

    @Test
    @DisplayName("缺少行情时当前市值和估值情况不再回退旧快照")
    void listPoolNoLongerFallsBackToPersistedDerivedSnapshots() {
        InvestStockPool pool = pool(1, "688610.SH", "埃科光电", "tech_vc", 10);
        pool.setCurrentMarketCap(new BigDecimal("144.70"));
        pool.setYtdGainPct(new BigDecimal("244.28"));
        pool.setValuationRange("泡沫");
        pool.setRevenueForecastY1(new BigDecimal("10.15"));
        pool.setRevenueForecastY2(new BigDecimal("14.08"));

        TradeStockBasic basic = new TradeStockBasic();
        basic.setStockCode("688610.SH");
        basic.setStockName("埃科光电");
        basic.setTotalShares(68_000_000L);

        when(poolRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pool));
        when(stockBasicRepo.findByStockCodeIn(any())).thenReturn(List.of(basic));
        when(financialRepo.findLatestByStockCodes(List.of("688610.SH"))).thenReturn(Collections.emptyList());
        when(quoteService.fetchQuotes(List.of("688610.SH"))).thenReturn(Collections.emptyMap());
        when(quoteService.fetchYearStartCloses(eq(List.of("688610.SH")), any())).thenReturn(Collections.emptyMap());

        PoolItemDTO item = service.listPool().get(0);

        assertThat(item.getCurrentMarketCap()).isNull();
        assertThat(item.getMarketCap()).isNull();
        assertThat(item.getYtdGainPct()).isNull();
        assertThat(item.getYtdGain()).isNull();
        assertThat(item.getValuationRange()).isNull();
    }

    private InvestStockPool pool(Integer id, String code, String name, String poolType, Integer displayOrder) {
        InvestStockPool pool = new InvestStockPool();
        pool.setId(id);
        pool.setStockCode(code);
        pool.setStockName(name);
        pool.setPoolType(poolType);
        pool.setDisplayOrder(displayOrder);
        return pool;
    }
}
