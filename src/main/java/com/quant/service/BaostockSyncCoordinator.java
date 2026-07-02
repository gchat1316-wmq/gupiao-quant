package com.quant.service;

import com.quant.config.BaostockSyncProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BaostockSyncCoordinator {

    private final BaostockSyncProperties properties;
    private final BaostockSyncService syncService;
    private final NotificationService notificationService;

    public BaostockSyncCoordinator(BaostockSyncProperties properties,
                                   BaostockSyncService syncService,
                                   NotificationService notificationService) {
        this.properties = properties;
        this.syncService = syncService;
        this.notificationService = notificationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        if (!properties.isEnabled() || !properties.isStartupEnabled()) {
            return;
        }
        safeSync("startup", properties.getStartupDaysBack());
    }

    @Scheduled(cron = "${baostock-sync.daily-cron:0 20 18 * * MON-FRI}")
    public void runDaily() {
        if (!properties.isEnabled()) {
            return;
        }
        safeSync("daily", properties.getDailyDaysBack());
    }

    /**
     * BaoStock 财务（trade_stock_financial）周期同步。仅 INSERT 缺失的 (code, date)，
     * 不会覆盖现有的 qmt/wind 历史数据。
     */
    @Scheduled(cron = "${baostock-sync.financial-cron:0 30 19 * * MON-FRI}")
    public void runFinancial() {
        if (!properties.isEnabled() || !properties.isFinancialEnabled()) {
            return;
        }
        try {
            syncService.syncFinancialOnly("financial-scheduled");
        } catch (Exception e) {
            log.warn("BaoStock financial sync failed, err={}", e.getMessage());
            try {
                notificationService.sendServerChan("BaoStock财务同步失败", "错误: " + e.getMessage());
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void safeSync(String reason, int daysBack) {
        try {
            syncService.syncNow(reason, daysBack);
        } catch (Exception e) {
            log.warn("BaoStock sync failed, reason={}, err={}", reason, e.getMessage());
            try {
                notificationService.sendServerChan("BaoStock同步失败", "触发方式: " + reason + "\n错误: " + e.getMessage());
            } catch (Exception ignored) {
                // ignore secondary failure
            }
        }
    }
}
