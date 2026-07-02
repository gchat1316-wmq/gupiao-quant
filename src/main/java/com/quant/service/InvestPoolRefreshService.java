package com.quant.service;

import com.quant.entity.InvestStockPool;
import com.quant.repository.InvestStockPoolRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 龙江投资股票池的后台补全任务。
 *
 * 1. {@link #refreshWeekly()} —— 每周六 20:30 拉 BaoStock 日线，再回填所有 tech_ai 池条目。
 * 2. {@link #backfillMissingFields()} —— 每天 16:30 扫一次所有池条目，把缺失字段（历史营收、Q1 财务、预测、近5年最低PS）从本地财务表回填。
 *
 * 回填策略：仅当目标字段为 NULL 时才覆盖，避免破坏手工录入（如 OCR 截图导入的数据）。
 */
@Slf4j
@Service
public class InvestPoolRefreshService {

    static final String POOL_TYPE = "tech_ai";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);
    private static final int MIN_PS_LOOKBACK_YEARS = 5;

    private final InvestStockPoolRepository poolRepository;
    private final BaostockSyncService baostockSyncService;
    private final InvestForecastProvider forecastProvider;
    private final AStockDataForecastProvider aStockDataForecastProvider;

    public InvestPoolRefreshService(InvestStockPoolRepository poolRepository,
                                    BaostockSyncService baostockSyncService,
                                    InvestForecastProvider forecastProvider,
                                    AStockDataForecastProvider aStockDataForecastProvider) {
        this.poolRepository = poolRepository;
        this.baostockSyncService = baostockSyncService;
        this.forecastProvider = forecastProvider;
        this.aStockDataForecastProvider = aStockDataForecastProvider;
    }

    @Scheduled(cron = "${invest-pool.refresh-cron:0 30 20 ? * SAT}")
    public void refreshWeekly() {
        try {
            baostockSyncService.syncNow("invest-pool-weekly", 370);
        } catch (Exception e) {
            log.warn("BaoStock sync failed before invest pool refresh: {}", e.getMessage());
        }
        // 周末全量刷新，覆盖所有 pool_type 的条目，确保 quality + tech_ai 的 NULL 字段都能补齐
        int count = refreshAllPoolSnapshots();
        log.info("invest pool weekly refresh done, touched={}", count);
    }

    /**
     * 每日检查：扫所有股票池条目（不限于 tech_ai），补齐缺失字段。
     * 主要修复 trade_stock_financial 已有但 invest_stock_pool 留空的字段。
     */
    @Scheduled(cron = "${invest-pool.backfill-cron:0 30 16 * * ?}")
    @CacheEvict(value = "stockPool", allEntries = true)
    public void backfillMissingFields() {
        try {
            List<InvestStockPool> all = poolRepository.findAll();
            int touched = 0;
            int fieldsFilled = 0;
            for (InvestStockPool pool : all) {
                int before = countFilledFields(pool);
                backfillOne(pool, true);
                int after = countFilledFields(pool);
                if (after > before) {
                    poolRepository.save(pool);
                    touched++;
                    fieldsFilled += (after - before);
                }
            }
            if (touched > 0) {
                log.info("invest pool backfill: touched={} fieldsFilled={}", touched, fieldsFilled);
            }
        } catch (Exception e) {
            log.warn("invest pool daily backfill failed: {}", e.getMessage(), e);
        }
    }

    @CacheEvict(value = "stockPool", allEntries = true)
    @Transactional
    public int refreshTechAiSnapshots() {
        List<InvestStockPool> pools = poolRepository.findByPoolTypeOrderByCreatedAtDesc(POOL_TYPE);
        int refreshed = 0;
        for (InvestStockPool pool : pools) {
            refreshOne(pool);
            poolRepository.save(pool);
            refreshed++;
        }
        return refreshed;
    }

    /**
     * 扫描所有股票池条目（不限 pool_type），仅补 NULL 字段。
     * 用于"修复所有缺失数据"的运维入口，定期巡检也用这个逻辑。
     */
    @CacheEvict(value = "stockPool", allEntries = true)
    @Transactional
    public int refreshAllPoolSnapshots() {
        List<InvestStockPool> pools = poolRepository.findAll();
        int refreshed = 0;
        for (InvestStockPool pool : pools) {
            refreshOne(pool);
            poolRepository.save(pool);
            refreshed++;
        }
        return refreshed;
    }

    /** 全量刷新（含手工字段也会刷新），仅周末定时任务调用。
     *  注意：手工录入字段（如 OCR 导入、seed 写入）不会被覆盖，只补 NULL。 */
    private void refreshOne(InvestStockPool pool) {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder errors = new StringBuilder();
        pool.setCurrentMarketCap(null);
        pool.setYtdGainPct(null);
        pool.setValuationRange(null);

        try {
            forecastProvider.fetchRevenueForecast(pool).ifPresent(forecast -> {
                // 预测字段：保留手工值，只填 NULL（避免覆盖 OCR 识别结果）
                if (pool.getRevenueForecastY0() == null && forecast.revenueForecastY0() != null) {
                    pool.setRevenueForecastY0(forecast.revenueForecastY0());
                }
                if (pool.getRevenueForecastY1() == null && forecast.revenueForecastY1() != null) {
                    pool.setRevenueForecastY1(forecast.revenueForecastY1());
                }
                if (pool.getRevenueForecastY2() == null && forecast.revenueForecastY2() != null) {
                    pool.setRevenueForecastY2(forecast.revenueForecastY2());
                }
            });
        } catch (Exception e) {
            appendError(errors, "预测刷新失败: " + e.getMessage());
        }

        try {
            backfillOne(pool, true);
        } catch (Exception e) {
            appendError(errors, "字段回填失败: " + e.getMessage());
        }

        pool.setPoolDataUpdatedAt(now);
        pool.setPoolUpdateError(errors.isEmpty() ? null : errors.toString());
    }

    /**
     * 把财务表里有但股票池里为空的字段补齐。仅补 NULL。
     *
     * @param pool 条目
     * @param softOnly true=只补 NULL（每日巡检）；false=覆盖（周末全量 refresh）
     */
    private void backfillOne(InvestStockPool pool, boolean softOnly) {
        AStockDataForecastProvider.FinancialSnapshot snapshot =
                aStockDataForecastProvider.loadFinancialSnapshot(pool.getStockCode());
        if (snapshot == null) {
            return;
        }
        int latestYear = snapshot.latestYear();
        // 历史营收：尝试补 2023/2024/2025 三档，按最新年报所在年份往前推
        int[] yearsToBackfill = latestYear >= 2026 ? new int[]{2023, 2024, 2025}
                : latestYear >= 2025 ? new int[]{2023, 2024}
                : latestYear >= 2024 ? new int[]{2023}
                : new int[0];
        for (int year : yearsToBackfill) {
            BigDecimal annual = snapshot.annualRevenueYi(year);
            if (annual == null) continue;
            switch (year) {
                case 2023 -> assignIfEmpty(pool::getRevenue2023, pool::setRevenue2023, annual, softOnly);
                case 2024 -> assignIfEmpty(pool::getRevenue2024, pool::setRevenue2024, annual, softOnly);
                case 2025 -> assignIfEmpty(pool::getRevenue2025, pool::setRevenue2025, annual, softOnly);
                default -> { /* 其他年份暂不写 */ }
            }
        }

        // Q1 财务：取最新一个季度的指标（业务上把它当作"最新季报"，与现有 q1_* 字段对应）
        BigDecimal gross = snapshot.latestGrossMargin();
        BigDecimal net = snapshot.latestNetMargin();
        BigDecimal yoy = snapshot.latestRevenueYoy();
        if (gross != null && (softOnly ? pool.getQ1GrossMargin() == null : true)) {
            pool.setQ1GrossMargin(gross.setScale(2, RoundingMode.HALF_UP));
        }
        if (net != null && (softOnly ? pool.getQ1NetMargin() == null : true)) {
            pool.setQ1NetMargin(net.setScale(2, RoundingMode.HALF_UP));
        }
        if (yoy != null && (softOnly ? pool.getQ1RevenueGrowth() == null : true)) {
            pool.setQ1RevenueGrowth(yoy);
        }

        // 近 5 年最低 PS：取最新市值 / TTM 营收近似。
        // 注意：当前没有历史市值接口，先用"当前 PS"作为兜底，避免一直 NULL。
        if (pool.getMinPs5y() == null || !softOnly) {
            BigDecimal approxPs = approxCurrentPs(pool, snapshot);
            if (approxPs != null) {
                pool.setMinPs5y(approxPs);
            }
        }
    }

    private void assignIfEmpty(java.util.function.Supplier<BigDecimal> getter,
                               java.util.function.Consumer<BigDecimal> setter,
                               BigDecimal value,
                               boolean softOnly) {
        if (softOnly && getter.get() != null) {
            return;
        }
        setter.accept(value);
    }

    /**
     * 用最近一条 quote 快照的市值 / TTM 营收估算"近5年最低PS"的近似值。
     * 历史市值接口不在当前服务内，先用当前 PS 兜底；后续接 QMT/xtdata 历史 K 线后可改为真·5年最低。
     */
    private BigDecimal approxCurrentPs(InvestStockPool pool,
                                      AStockDataForecastProvider.FinancialSnapshot snapshot) {
        try {
            BigDecimal latestRevenueYi = aStockDataForecastProvider.toYi(snapshot.latest().getRevenue());
            if (latestRevenueYi == null || latestRevenueYi.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            // 当前市值不在 InvestStockPool 上持久化，listPool 时实时算；refresh 时拿不到实时行情。
            // 这里用"目标市值"或"股票池中已存 currentMarketCap"做兜底，如果都没有就返回 null。
            BigDecimal marketCapYi = pool.getCurrentMarketCap();
            if (marketCapYi == null) {
                marketCapYi = pool.getTargetMarketCap();
            }
            if (marketCapYi == null || marketCapYi.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            return marketCapYi.divide(latestRevenueYi, 2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    private int countFilledFields(InvestStockPool pool) {
        int n = 0;
        if (pool.getRevenue2023() != null) n++;
        if (pool.getRevenue2024() != null) n++;
        if (pool.getRevenue2025() != null) n++;
        if (pool.getRevenueForecastY0() != null) n++;
        if (pool.getRevenueForecastY1() != null) n++;
        if (pool.getRevenueForecastY2() != null) n++;
        if (pool.getQ1GrossMargin() != null) n++;
        if (pool.getQ1NetMargin() != null) n++;
        if (pool.getQ1RevenueGrowth() != null) n++;
        if (pool.getMinPs5y() != null) n++;
        return n;
    }

    private void appendError(StringBuilder errors, String error) {
        if (!errors.isEmpty()) {
            errors.append("; ");
        }
        errors.append(error);
    }
}