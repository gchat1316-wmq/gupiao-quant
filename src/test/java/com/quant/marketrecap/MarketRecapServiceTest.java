package com.quant.marketrecap;

import com.quant.dto.marketrecap.KeyDataItemDTO;
import com.quant.dto.marketrecap.MarketRecapBadgeDTO;
import com.quant.dto.marketrecap.MarketRecapPageDTO;
import com.quant.dto.marketrecap.SectorCardDTO;
import com.quant.dto.marketrecap.StrategyItemDTO;
import com.quant.entity.InvestMarketRecap;
import com.quant.repository.InvestMarketRecapRepository;
import com.quant.service.MarketRecapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MarketRecapService")
class MarketRecapServiceTest {

    @Mock
    InvestMarketRecapRepository repository;

    MarketRecapService service;

    @BeforeEach
    void setUp() {
        service = new MarketRecapService(repository);
    }

    @Test
    @DisplayName("默认优先返回 A股 页面，并解析结构字段")
    void loadsPreferredMarketAndParsesStructuredFields() {
        when(repository.findDistinctMarkets()).thenReturn(List.of("港股", "A股"));
        when(repository.findByMarketOrderByTradeDateDescIdDesc("A股")).thenReturn(List.of(sampleRecap()));

        MarketRecapPageDTO page = service.getPage(null);

        assertThat(page.getSelectedMarket()).isEqualTo("A股");
        assertThat(page.getMarkets()).containsExactly("A股", "港股");
        assertThat(page.getTimeline()).hasSize(1);
        assertThat(page.getLatest()).isNotNull();
        assertThat(page.getLatest().getSummaryExcerpt()).contains("科技暴力反弹");
        assertThat(page.getLatest().getSectors())
                .extracting(SectorCardDTO::getName)
                .containsExactly("半导体");
        assertThat(page.getLatest().getSectors().get(0).getLeaders())
                .containsExactly("沪硅产业", "富创精密", "杰华特");
        assertThat(page.getLatest().getKeyData())
                .extracting(KeyDataItemDTO::getLabel)
                .contains("涨停溢价", "海外催化 / 费城半导体");
        assertThat(page.getLatest().getNextDayStrategy())
                .extracting(StrategyItemDTO::getLabel)
                .contains("持仓者", "仓位");
        assertThat(page.getLatest().getContentHtml()).contains("<h1>", "<table");
    }

    @Test
    @DisplayName("策略纯文本时退化为单条策略项")
    void fallsBackToSingleStrategyItemForPlainText() {
        InvestMarketRecap recap = sampleRecap();
        recap.setNextDayStrategy("中仓参与，不追高，等回调低吸");

        when(repository.findDistinctMarkets()).thenReturn(List.of("A股"));
        when(repository.findByMarketOrderByTradeDateDescIdDesc("A股")).thenReturn(List.of(recap));

        MarketRecapPageDTO page = service.getPage("A股");

        assertThat(page.getLatest().getNextDayStrategy()).hasSize(1);
        assertThat(page.getLatest().getNextDayStrategy().get(0).getLabel()).isEqualTo("策略");
        assertThat(page.getLatest().getNextDayStrategy().get(0).getValue()).isEqualTo("中仓参与，不追高，等回调低吸");
    }

    @Test
    @DisplayName("getBadgeSummary 统计今天/昨天的复盘数")
    void badgeSummaryCountsTodayAndYesterday() {
        LocalDate ref = LocalDate.of(2026, 6, 17);
        InvestMarketRecap todayA = sampleRecap();
        todayA.setId(13L);
        todayA.setTradeDate(ref);
        todayA.setMarket("A股");

        InvestMarketRecap todayB = sampleRecap();
        todayB.setId(14L);
        todayB.setTradeDate(ref);
        todayB.setMarket("港股");

        InvestMarketRecap yestA = sampleRecap();
        yestA.setId(11L);
        yestA.setTradeDate(ref.minusDays(1));
        yestA.setMarket("A股");

        InvestMarketRecap yestB = sampleRecap();
        yestB.setId(12L);
        yestB.setTradeDate(ref.minusDays(1));
        yestB.setMarket("美股");

        // repo 已按 tradeDate desc 排好
        when(repository.findAllByOrderByTradeDateDescIdDesc())
                .thenReturn(List.of(todayA, todayB, yestA, yestB));

        MarketRecapBadgeDTO badge = service.getBadgeSummary(ref);

        assertThat(badge.getToday()).isEqualTo(2);
        assertThat(badge.getYesterday()).isEqualTo(2);
        assertThat(badge.getLatestId()).isEqualTo(13L);
        assertThat(badge.getLatestTradeDate()).isEqualTo("2026-06-17");
    }

    @Test
    @DisplayName("getBadgeSummary 无数据时返回零和 null")
    void badgeSummaryEmpty() {
        when(repository.findAllByOrderByTradeDateDescIdDesc()).thenReturn(List.of());

        MarketRecapBadgeDTO badge = service.getBadgeSummary(LocalDate.of(2026, 6, 17));

        assertThat(badge.getToday()).isZero();
        assertThat(badge.getYesterday()).isZero();
        assertThat(badge.getLatestId()).isNull();
        assertThat(badge.getLatestTradeDate()).isNull();
    }

    @Test
    @DisplayName("getBadgeSummary 跳过 tradeDate 为空的记录")
    void badgeSummarySkipsNullTradeDate() {
        LocalDate ref = LocalDate.of(2026, 6, 17);
        InvestMarketRecap nullDate = sampleRecap();
        nullDate.setId(99L);
        nullDate.setTradeDate(null);

        InvestMarketRecap today = sampleRecap();
        today.setId(13L);
        today.setTradeDate(ref);

        when(repository.findAllByOrderByTradeDateDescIdDesc())
                .thenReturn(List.of(nullDate, today));

        MarketRecapBadgeDTO badge = service.getBadgeSummary(ref);

        assertThat(badge.getToday()).isEqualTo(1);
        assertThat(badge.getYesterday()).isZero();
        // 跳过 null tradeDate,latestId 取到第一条有效记录
        assertThat(badge.getLatestId()).isEqualTo(13L);
        assertThat(badge.getLatestTradeDate()).isEqualTo("2026-06-17");
    }

    @Test
    @DisplayName("getBadgeSummary 遇到 2 天前的记录提前 break")
    void badgeSummaryBreaksEarlyOnOldData() {
        LocalDate ref = LocalDate.of(2026, 6, 17);
        List<InvestMarketRecap> recaps = new ArrayList<>();
        // 模拟 repo 返回: 今天, 昨天, 前天, 更早
        InvestMarketRecap today = sampleRecap();
        today.setId(13L);
        today.setTradeDate(ref);
        recaps.add(today);

        InvestMarketRecap yest = sampleRecap();
        yest.setId(12L);
        yest.setTradeDate(ref.minusDays(1));
        recaps.add(yest);

        // 前天及更早就 break 了,这些不会被遍历
        for (int i = 0; i < 5; i++) {
            InvestMarketRecap old = sampleRecap();
            old.setId(100L + i);
            old.setTradeDate(ref.minusDays(2));
            recaps.add(old);
        }
        when(repository.findAllByOrderByTradeDateDescIdDesc()).thenReturn(recaps);

        MarketRecapBadgeDTO badge = service.getBadgeSummary(ref);

        assertThat(badge.getToday()).isEqualTo(1);
        assertThat(badge.getYesterday()).isEqualTo(1);
    }

    private static <T> T any(Class<T> clazz) {
        return org.mockito.ArgumentMatchers.any(clazz);
    }

    private InvestMarketRecap sampleRecap() {
        InvestMarketRecap recap = new InvestMarketRecap();
        recap.setId(1L);
        recap.setMarket("A股");
        recap.setRecapDate(LocalDate.of(2026, 6, 9));
        recap.setTradeDate(LocalDate.of(2026, 6, 9));
        recap.setTitle("A股2026-06-09盘后复盘：科技暴力反弹，接力资金不足");
        recap.setContent("""
                # 2026-06-09 A股盘后复盘

                ## 一、指数
                - 上证指数：**+1.28%**（4010点）
                - 深成指：+3.02%

                | 日期 | PCB | 半导体 |
                |------|------|--------|
                | 6/9  | +9.04% | +11.78% |
                """);
        recap.setIndexesSummary("上证+1.28%(4010) 深成指+3.02% 创业板+3.93%");
        recap.setAdvanceDecline("1.62:1");
        recap.setLimitUp(210);
        recap.setLimitDown(15);
        recap.setSentiment("冰点后的反弹/启动期(非高潮)");
        recap.setSectors("""
                [{"name":"半导体","涨停数":"4+","标的":["沪硅产业","富创精密","杰华特"],"核心龙头":"兆易创新","催化":["费城半导体+5.61%","英特尔+11%"]}]
                """);
        recap.setRisks("""
                ["反弹第一天，持续性存疑","涨停溢价低(1.97%)，接力资金不足"]
                """);
        recap.setCatalysts("""
                ["美股费城半导体指数 +5.61%","英特尔 +11%","美光 +10%"]
                """);
        recap.setKeyData("\"{\\\"涨停溢价\\\":\\\"1.97%\\\",\\\"海外催化\\\":{\\\"费城半导体\\\":\\\"+5.61%\\\",\\\"英特尔\\\":\\\"+11%\\\"}}\"");
        recap.setNextDayStrategy("""
                {"持仓者":"持有","仓位":"中仓参与，不追高，等回调低吸"}
                """);
        return recap;
    }
}
