package com.quant.service;

import com.quant.entity.InvestStockPool;
import com.quant.repository.InvestStockPoolRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class InvestPoolRefreshService {

    static final String POOL_TYPE = "tech_vc";

    private final InvestStockPoolRepository poolRepository;
    private final BaostockSyncService baostockSyncService;
    private final InvestForecastProvider forecastProvider;

    public InvestPoolRefreshService(InvestStockPoolRepository poolRepository,
                                    BaostockSyncService baostockSyncService,
                                    InvestForecastProvider forecastProvider) {
        this.poolRepository = poolRepository;
        this.baostockSyncService = baostockSyncService;
        this.forecastProvider = forecastProvider;
    }

    @Scheduled(cron = "${invest-pool.refresh-cron:0 30 20 ? * SAT}")
    public void refreshWeekly() {
        try {
            baostockSyncService.syncNow("invest-pool-weekly", 370);
        } catch (Exception e) {
            log.warn("BaoStock sync failed before invest pool refresh: {}", e.getMessage());
        }
        refreshTechVcSnapshots();
    }

    @Transactional
    public int refreshTechVcSnapshots() {
        int refreshed = 0;
        for (InvestStockPool pool : poolRepository.findByPoolTypeOrderByCreatedAtDesc(POOL_TYPE)) {
            refreshOne(pool);
            poolRepository.save(pool);
            refreshed++;
        }
        return refreshed;
    }

    private void refreshOne(InvestStockPool pool) {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder errors = new StringBuilder();
        pool.setCurrentMarketCap(null);
        pool.setYtdGainPct(null);
        pool.setValuationRange(null);

        try {
            forecastProvider.fetchRevenueForecast(pool).ifPresent(forecast -> {
                if (forecast.revenueForecastY0() != null) pool.setRevenueForecastY0(forecast.revenueForecastY0());
                if (forecast.revenueForecastY1() != null) pool.setRevenueForecastY1(forecast.revenueForecastY1());
                if (forecast.revenueForecastY2() != null) pool.setRevenueForecastY2(forecast.revenueForecastY2());
            });
        } catch (Exception e) {
            appendError(errors, "预测刷新失败: " + e.getMessage());
        }

        pool.setPoolDataUpdatedAt(now);
        pool.setPoolUpdateError(errors.isEmpty() ? null : errors.toString());
    }

    private void appendError(StringBuilder errors, String error) {
        if (!errors.isEmpty()) {
            errors.append("; ");
        }
        errors.append(error);
    }
}
