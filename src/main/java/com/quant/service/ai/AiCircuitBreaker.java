package com.quant.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 简易 AI 服务熔断器。
 *
 * <p>痛点: MiniMax API key 在 2026-06-26 已过期 (401 authentication_error)，
 * 板块叙事流水线每次都先调 MiniMax，等 90s 超时后才回退 SenseNova，
 * 5 个板块 × 90s = 7.5 分钟白等。</p>
 *
 * <p>方案: 累计 N 次 401/403 后熔断 30 分钟，期间直接跳过该 provider。
 * 仅对 authentication_error / 401 / 403 触发熔断，
 * 业务错误（400 bad_request / 500 server_error）不熔断。</p>
 */
@Slf4j
@Component
public class AiCircuitBreaker {

    private static final int FAILURE_THRESHOLD = 3;          // 熔断阈值
    private static final Duration OPEN_DURATION = Duration.ofMinutes(30);  // 熔断 30 分钟

    private final AtomicInteger consecutiveAuthFailures = new AtomicInteger(0);
    private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.EPOCH);

    /**
     * 调用 AI 失败时上报。返回 true 表示已熔断。
     */
    public boolean recordAuthFailure(String provider) {
        int count = consecutiveAuthFailures.incrementAndGet();
        if (count >= FAILURE_THRESHOLD) {
            Instant until = Instant.now().plus(OPEN_DURATION);
            openUntil.set(until);
            log.error("AI provider [{}] 连续 {} 次鉴权失败，已熔断至 {}。期间将直接跳过该 provider。",
                    provider, count, until);
            return true;
        }
        log.warn("AI provider [{}] 鉴权失败累计 {} 次，达到 {} 次后熔断", provider, count, FAILURE_THRESHOLD);
        return false;
    }

    /**
     * 调用成功时重置计数。
     */
    public void recordSuccess(String provider) {
        int before = consecutiveAuthFailures.getAndSet(0);
        if (before > 0 || openUntil.get().isAfter(Instant.now())) {
            log.info("AI provider [{}] 恢复正常，重置熔断状态（之前 {} 次失败）", provider, before);
            openUntil.set(Instant.EPOCH);
        }
    }

    /**
     * 当前是否在熔断窗口内。
     */
    public boolean isOpen(String provider) {
        Instant until = openUntil.get();
        if (until.isAfter(Instant.now())) {
            log.debug("AI provider [{}] 熔断中，跳过 (剩余 {}s)", provider,
                    Duration.between(Instant.now(), until).toSeconds());
            return true;
        }
        return false;
    }

    /**
     * 当前熔断状态（供诊断页 / actuator）。
     */
    public String getStatus() {
        Instant until = openUntil.get();
        boolean open = until.isAfter(Instant.now());
        return open
                ? String.format("OPEN until %s (failures=%d)", until, consecutiveAuthFailures.get())
                : String.format("CLOSED (failures=%d)", consecutiveAuthFailures.get());
    }
}
