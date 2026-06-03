package com.quant.service.prosperitystrong;

import com.quant.config.ProsperityStrongProperties;
import com.quant.dto.prosperitystrong.PipelineRunResultDTO;
import com.quant.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProsperityStrongScheduler {

    private final ProsperityStrongProperties props;
    private final ProsperityStrongPipelineService pipeline;
    private final NotificationService notification;

    @Scheduled(cron = "${prosperity-strong.cron:0 30 15 * * MON-FRI}")
    public void runDaily() {
        if (!props.isEnabled()) {
            log.info("强势股选股流水线未启用,跳过定时执行");
            return;
        }
        LocalDate today = LocalDate.now();
        int maxAttempts = 3;
        Exception lastErr = null;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                PipelineRunResultDTO r = pipeline.run(today, props.getProvider());
                log.info("强势股流水线完成: {}", r.getMessage());
                return;
            } catch (Exception e) {
                lastErr = e;
                log.warn("强势股流水线第 {} 次执行失败: {}", i, e.getMessage());
                try { Thread.sleep(5 * 60_000L); } catch (InterruptedException ignored) {}
            }
        }
        String title = "强势股选股流水线连续失败";
        String content = "已重试 " + maxAttempts + " 次,最近错误: " + (lastErr == null ? "?" : lastErr.getMessage());
        try {
            notification.sendServerChan(title, content);
        } catch (Exception ignored) {}
    }
}
