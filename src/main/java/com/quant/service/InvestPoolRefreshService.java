package com.quant.service;

import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
public class InvestPoolRefreshService {

    static final String POOL_TYPE = "tech_vc";

    private final InvestStockPoolRepository poolRepository;
    private final TradeStockBasicRepository stockBasicRepository;
    private final TradeStockDailyRepository dailyRepository;
    private final BaostockSyncService baostockSyncService;
    private final InvestForecastProvider forecastProvider;

    public InvestPoolRefreshService(InvestStockPoolRepository poolRepository,
                                    TradeStockBasicRepository stockBasicRepository,
                                    TradeStockDailyRepository dailyRepository,
                                    BaostockSyncService baostockSyncService,
                                    InvestForecastProvider forecastProvider) {
        this.poolRepository = poolRepository;
        this.stockBasicRepository = stockBasicRepository;
        this.dailyRepository = dailyRepository;
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
        Optional<TradeStockBasic> basicOpt = stockBasicRepository.findByStockCode(pool.getStockCode());
        Optional<TradeStockDaily> latestOpt = dailyRepository.findFirstByStockCodeOrderByTradeDateDesc(pool.getStockCode());
        Optional<TradeStockDaily> yearStartOpt = dailyRepository
                .findFirstByStockCodeAndTradeDateGreaterThanEqualOrderByTradeDateAsc(
                        pool.getStockCode(), LocalDate.of(LocalDate.now().getYear(), 1, 1));

        BigDecimal marketCap = computeMarketCap(latestOpt.orElse(null), basicOpt.orElse(null));
        if (marketCap != null) {
            pool.setCurrentMarketCap(marketCap);
        } else {
            appendError(errors, "当前市值缺少最新价或总股本");
        }

        BigDecimal ytdGain = computeYtdGain(latestOpt.orElse(null), yearStartOpt.orElse(null));
        if (ytdGain != null) {
            pool.setYtdGainPct(ytdGain);
        } else {
            appendError(errors, "今年涨幅缺少最新价或年初价");
        }

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

    private BigDecimal computeMarketCap(TradeStockDaily latest, TradeStockBasic basic) {
        if (latest == null || latest.getClosePrice() == null || basic == null || basic.getTotalShares() == null) {
            return null;
        }
        return BigDecimal.valueOf(basic.getTotalShares())
                .multiply(latest.getClosePrice())
                .divide(BigDecimal.valueOf(100_000_000L), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeYtdGain(TradeStockDaily latest, TradeStockDaily yearStart) {
        if (latest == null || yearStart == null || latest.getClosePrice() == null || yearStart.getClosePrice() == null
                || yearStart.getClosePrice().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return latest.getClosePrice().subtract(yearStart.getClosePrice())
                .divide(yearStart.getClosePrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private void appendError(StringBuilder errors, String error) {
        if (!errors.isEmpty()) {
            errors.append("; ");
        }
        errors.append(error);
    }
}
