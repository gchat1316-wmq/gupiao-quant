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
