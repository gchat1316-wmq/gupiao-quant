package com.quant.invest;

import com.quant.entity.InvestStockPool;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.service.BaostockSyncService;
import com.quant.service.InvestForecastProvider;
import com.quant.service.InvestPoolRefreshService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("InvestPoolRefreshService")
class InvestPoolRefreshServiceTest {

    @Test
    @DisplayName("预测源失败时保留旧预测值并清空派生快照字段")
    void refreshKeepsForecastsWhenProviderFails() {
        InvestStockPoolRepository poolRepo = mock(InvestStockPoolRepository.class);
        BaostockSyncService syncService = mock(BaostockSyncService.class);
        InvestForecastProvider forecastProvider = mock(InvestForecastProvider.class);

        InvestStockPool pool = new InvestStockPool();
        pool.setId(1);
        pool.setStockCode("688610.SH");
        pool.setPoolType("tech_vc");
        pool.setRevenueForecastY0(new BigDecimal("6.87"));
        pool.setRevenueForecastY1(new BigDecimal("10.15"));
        pool.setRevenueForecastY2(new BigDecimal("14.08"));
        pool.setCurrentMarketCap(new BigDecimal("20.00"));
        pool.setYtdGainPct(new BigDecimal("100.00"));
        pool.setValuationRange("合理");

        when(poolRepo.findByPoolTypeOrderByCreatedAtDesc("tech_vc")).thenReturn(List.of(pool));
        when(forecastProvider.fetchRevenueForecast(pool)).thenThrow(new IllegalStateException("source unavailable"));

        InvestPoolRefreshService service = new InvestPoolRefreshService(
                poolRepo, syncService, forecastProvider);

        int refreshed = service.refreshTechVcSnapshots();

        assertThat(refreshed).isEqualTo(1);
        assertThat(pool.getCurrentMarketCap()).isNull();
        assertThat(pool.getYtdGainPct()).isNull();
        assertThat(pool.getValuationRange()).isNull();
        assertThat(pool.getRevenueForecastY0()).isEqualByComparingTo("6.87");
        assertThat(pool.getRevenueForecastY1()).isEqualByComparingTo("10.15");
        assertThat(pool.getRevenueForecastY2()).isEqualByComparingTo("14.08");
        assertThat(pool.getPoolUpdateError()).contains("预测刷新失败");
    }
}
