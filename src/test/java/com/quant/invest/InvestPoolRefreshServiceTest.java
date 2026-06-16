package com.quant.invest;

import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.service.BaostockSyncService;
import com.quant.service.InvestForecastProvider;
import com.quant.service.InvestPoolRefreshService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("InvestPoolRefreshService")
class InvestPoolRefreshServiceTest {

    @Test
    @DisplayName("预测源失败时保留旧预测值并继续更新当前市值和今年涨幅")
    void refreshKeepsForecastsWhenProviderFails() {
        InvestStockPoolRepository poolRepo = mock(InvestStockPoolRepository.class);
        TradeStockBasicRepository basicRepo = mock(TradeStockBasicRepository.class);
        TradeStockDailyRepository dailyRepo = mock(TradeStockDailyRepository.class);
        BaostockSyncService syncService = mock(BaostockSyncService.class);
        InvestForecastProvider forecastProvider = mock(InvestForecastProvider.class);

        InvestStockPool pool = new InvestStockPool();
        pool.setId(1);
        pool.setStockCode("688610.SH");
        pool.setPoolType("tech_vc");
        pool.setRevenueForecastY0(new BigDecimal("6.87"));
        pool.setRevenueForecastY1(new BigDecimal("10.15"));
        pool.setRevenueForecastY2(new BigDecimal("14.08"));

        TradeStockBasic basic = new TradeStockBasic();
        basic.setStockCode("688610.SH");
        basic.setTotalShares(100_000_000L);

        TradeStockDaily latest = daily("688610.SH", LocalDate.of(2026, 6, 15), "20.00");
        TradeStockDaily yearStart = daily("688610.SH", LocalDate.of(2026, 1, 2), "10.00");

        when(poolRepo.findByPoolTypeOrderByCreatedAtDesc("tech_vc")).thenReturn(List.of(pool));
        when(basicRepo.findByStockCode("688610.SH")).thenReturn(Optional.of(basic));
        when(dailyRepo.findFirstByStockCodeOrderByTradeDateDesc("688610.SH")).thenReturn(Optional.of(latest));
        when(dailyRepo.findFirstByStockCodeAndTradeDateGreaterThanEqualOrderByTradeDateAsc(any(), any()))
                .thenReturn(Optional.of(yearStart));
        when(forecastProvider.fetchRevenueForecast(pool)).thenThrow(new IllegalStateException("source unavailable"));

        InvestPoolRefreshService service = new InvestPoolRefreshService(
                poolRepo, basicRepo, dailyRepo, syncService, forecastProvider);

        int refreshed = service.refreshTechVcSnapshots();

        assertThat(refreshed).isEqualTo(1);
        assertThat(pool.getCurrentMarketCap()).isEqualByComparingTo("20.00");
        assertThat(pool.getYtdGainPct()).isEqualByComparingTo("100.00");
        assertThat(pool.getRevenueForecastY0()).isEqualByComparingTo("6.87");
        assertThat(pool.getRevenueForecastY1()).isEqualByComparingTo("10.15");
        assertThat(pool.getRevenueForecastY2()).isEqualByComparingTo("14.08");
        assertThat(pool.getPoolUpdateError()).contains("预测刷新失败");
    }

    private TradeStockDaily daily(String code, LocalDate date, String close) {
        TradeStockDaily daily = new TradeStockDaily();
        daily.setStockCode(code);
        daily.setTradeDate(date);
        daily.setClosePrice(new BigDecimal(close));
        return daily;
    }
}
