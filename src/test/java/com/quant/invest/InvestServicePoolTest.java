package com.quant.invest;

import com.quant.dto.invest.PoolItemDTO;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.AStockDataQuoteService;
import com.quant.service.InvestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvestService - 股票池列表")
class InvestServicePoolTest {

    @Mock TradeStockBasicRepository stockBasicRepo;
    @Mock TradeStockFinancialRepository financialRepo;
    @Mock InvestStockPoolRepository poolRepo;
    @Mock InvestPositionCommonRepository positionRepo;
    @Mock AStockDataQuoteService quoteService;

    InvestService service;

    @BeforeEach
    void setUp() {
        service = new InvestService(stockBasicRepo, financialRepo, poolRepo, positionRepo, quoteService);
    }

    @Test
    @DisplayName("基础表缺失时使用股票池自身的公司名称")
    void listPoolUsesPoolStockNameWhenBasicMissing() {
        InvestStockPool pool = new InvestStockPool();
        pool.setId(14);
        pool.setStockCode("688296");
        pool.setStockName("金海通");
        pool.setPoolType("tech_ai");

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
    void listPoolOrdersTechAiByDisplayOrder() {
        InvestStockPool second = pool(2, "688515.SH", "裕太微", "tech_ai", 20);
        InvestStockPool quality = pool(3, "600519.SH", "贵州茅台", "quality", null);
        InvestStockPool first = pool(1, "688610.SH", "埃科光电", "tech_ai", 10);

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
        InvestStockPool pool = pool(1, "688610.SH", "埃科光电", "tech_ai", 10);
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
        InvestStockPool pool = pool(1, "688610.SH", "埃科光电", "tech_ai", 10);
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

    // ── 2026-07-01 弹窗"消息监控" checkbox 同步到 InvestPositionCommon ──

    @Nested
    @DisplayName("updatePool - 消息监控开关")
    class UpdatePoolAlertSync {

        /**
         * 弹窗里勾选"希望买入价 + 消息监控"→ 后端把 target_buy_price 写入 pool，
         * 并把 fixed_buy_enabled=1 + fixed_buy_price=target_buy_price 写入 InvestPositionCommon。
         * MonitorService 扫描到后会发 server 酱。
         */
        @Test
        @DisplayName("勾选买入监控 → fixed_buy_enabled=1 + fixed_buy_price=targetBuyPrice")
        void alertBuyEnabledSyncsToFixedBuy() {
            InvestStockPool pool = pool(1, "688525.SH", "佰维存储", "tech_ai", null);
            pool.setTargetBuyPrice(new BigDecimal("20.00"));

            InvestPositionCommon pos = new InvestPositionCommon();
            pos.setStockCode("688525.SH");
            pos.setPoolType("invest");

            PoolSaveRequest req = new PoolSaveRequest();
            req.setKeyword("688525.SH");
            req.setPoolType("tech_ai");
            req.setTargetBuyPrice(new BigDecimal("20.00"));
            req.setAlertBuyEnabled(true);

            when(poolRepo.findById(1)).thenReturn(Optional.of(pool));
            when(positionRepo.findByStockCodeAndPoolType("688525.SH", "invest")).thenReturn(Optional.of(pos));
            when(poolRepo.save(any())).thenReturn(pool);
            when(stockBasicRepo.findByStockCode("688525.SH")).thenReturn(Optional.empty());

            service.updatePool(1, req);

            ArgumentCaptor<InvestPositionCommon> posCaptor = ArgumentCaptor.forClass(InvestPositionCommon.class);
            verify(positionRepo).save(posCaptor.capture());
            InvestPositionCommon saved = posCaptor.getValue();
            assertThat(saved.getFixedBuyEnabled()).isEqualTo(1);
            assertThat(saved.getFixedBuyPrice()).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("勾选卖出监控 → fixed_sell_enabled=1 + fixed_sell_price=targetSellPrice")
        void alertSellEnabledSyncsToFixedSell() {
            InvestStockPool pool = pool(2, "688525.SH", "佰维存储", "tech_ai", null);
            InvestPositionCommon pos = new InvestPositionCommon();
            pos.setStockCode("688525.SH");
            pos.setPoolType("invest");
            pos.setTargetSellPrice(new BigDecimal("30.00"));

            PoolSaveRequest req = new PoolSaveRequest();
            req.setKeyword("688525.SH");
            req.setPoolType("tech_ai");
            req.setTargetSellPrice(new BigDecimal("30.00"));
            req.setAlertSellEnabled(true);

            when(poolRepo.findById(2)).thenReturn(Optional.of(pool));
            when(positionRepo.findByStockCodeAndPoolType("688525.SH", "invest")).thenReturn(Optional.of(pos));
            when(poolRepo.save(any())).thenReturn(pool);
            when(stockBasicRepo.findByStockCode("688525.SH")).thenReturn(Optional.empty());

            service.updatePool(2, req);

            ArgumentCaptor<InvestPositionCommon> posCaptor = ArgumentCaptor.forClass(InvestPositionCommon.class);
            verify(positionRepo).save(posCaptor.capture());
            InvestPositionCommon saved = posCaptor.getValue();
            assertThat(saved.getFixedSellEnabled()).isEqualTo(1);
            assertThat(saved.getFixedSellPrice()).isEqualByComparingTo("30.00");
        }

        @Test
        @DisplayName("取消监控 → fixed_buy_enabled=0 + fixed_buy_price=null")
        void disableAlertClearsFixedPrice() {
            InvestStockPool pool = pool(3, "688525.SH", "佰维存储", "tech_ai", null);
            pool.setTargetBuyPrice(new BigDecimal("20.00"));

            InvestPositionCommon pos = new InvestPositionCommon();
            pos.setStockCode("688525.SH");
            pos.setPoolType("invest");
            pos.setFixedBuyEnabled(1);
            pos.setFixedBuyPrice(new BigDecimal("20.00"));

            PoolSaveRequest req = new PoolSaveRequest();
            req.setKeyword("688525.SH");
            req.setPoolType("tech_ai");
            req.setTargetBuyPrice(new BigDecimal("20.00"));
            req.setAlertBuyEnabled(false);  // 用户取消勾选

            when(poolRepo.findById(3)).thenReturn(Optional.of(pool));
            when(positionRepo.findByStockCodeAndPoolType("688525.SH", "invest")).thenReturn(Optional.of(pos));
            when(poolRepo.save(any())).thenReturn(pool);
            when(stockBasicRepo.findByStockCode("688525.SH")).thenReturn(Optional.empty());

            service.updatePool(3, req);

            ArgumentCaptor<InvestPositionCommon> posCaptor = ArgumentCaptor.forClass(InvestPositionCommon.class);
            verify(positionRepo).save(posCaptor.capture());
            InvestPositionCommon saved = posCaptor.getValue();
            assertThat(saved.getFixedBuyEnabled()).isEqualTo(0);
            assertThat(saved.getFixedBuyPrice()).isNull();
        }

        @Test
        @DisplayName("不传 alertBuyEnabled → 维持现状（不修改 fixed_buy_enabled）")
        void alertUnchangedWhenNotProvided() {
            InvestStockPool pool = pool(4, "688525.SH", "佰维存储", "tech_ai", null);
            InvestPositionCommon pos = new InvestPositionCommon();
            pos.setStockCode("688525.SH");
            pos.setPoolType("invest");
            pos.setFixedBuyEnabled(1);
            pos.setFixedBuyPrice(new BigDecimal("15.00"));

            PoolSaveRequest req = new PoolSaveRequest();
            req.setKeyword("688525.SH");
            req.setPoolType("tech_ai");
            req.setTargetBuyPrice(new BigDecimal("18.00"));
            // 故意不设 alertBuyEnabled

            when(poolRepo.findById(4)).thenReturn(Optional.of(pool));
            when(positionRepo.findByStockCodeAndPoolType("688525.SH", "invest")).thenReturn(Optional.of(pos));
            when(poolRepo.save(any())).thenReturn(pool);
            when(stockBasicRepo.findByStockCode("688525.SH")).thenReturn(Optional.empty());

            service.updatePool(4, req);

            ArgumentCaptor<InvestPositionCommon> posCaptor = ArgumentCaptor.forClass(InvestPositionCommon.class);
            verify(positionRepo).save(posCaptor.capture());
            InvestPositionCommon saved = posCaptor.getValue();
            // 旧值保持
            assertThat(saved.getFixedBuyEnabled()).isEqualTo(1);
            assertThat(saved.getFixedBuyPrice()).isEqualByComparingTo("15.00");
        }
    }
}
