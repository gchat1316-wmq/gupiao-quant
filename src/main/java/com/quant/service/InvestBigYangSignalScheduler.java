package com.quant.service;

import com.quant.config.InvestBigYangProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InvestBigYangSignalScheduler {

    private final InvestBigYangProperties properties;
    private final InvestBigYangSignalService service;

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        if (!properties.isEnabled() || !properties.isStartupEnabled()) {
            return;
        }
        try {
            service.runManual();
        } catch (Exception e) {
            log.warn("大阳线战法启动扫描失败: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${invest-big-yang.candidate-cron:0 35 18 * * MON-FRI}")
    public void runCandidateScan() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            service.runCandidateScan();
        } catch (Exception e) {
            log.warn("大阳线战法候选扫描失败: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${invest-big-yang.trigger-cron:0 */5 9-15 * * MON-FRI}")
    public void runTriggerScan() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            service.runTriggerScan();
        } catch (Exception e) {
            log.warn("大阳线战法触发扫描失败: {}", e.getMessage());
        }
    }
}
