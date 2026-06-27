package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.service.techai.TechAiStockCodeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class EastMoneyRealtimeQuoteService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    /** 主域 502/timeout 时降级到备用域（与 aidaily 中台同源，已验证返回一致）。 */
    private static final String PRIMARY_HOST = "push2.eastmoney.com";
    private static final String BACKUP_HOST = "push2delay.eastmoney.com";

    /** 30s 窗口内主域连续失败 ≥3 次 → 切备用；30s 内主域任一成功 → 复位。 */
    private static final long FAILURE_WINDOW_MS = 30_000L;
    private static final int FAILURE_THRESHOLD = 3;
    private final AtomicLong firstFailureAt = new AtomicLong(0);
    private final AtomicInteger recentFailures = new AtomicInteger(0);

    public Map<String, TechAiQuoteSnapshot> fetch(Collection<String> codes) {
        Map<String, TechAiQuoteSnapshot> result = new HashMap<>();
        for (String code : codes) {
            TechAiQuoteSnapshot quote = fetchOne(code);
            if (quote != null && quote.getLatestPrice() != null) {
                result.put(TechAiStockCodeUtils.normalizeProjectCode(quote.getStockCode()), quote);
            }
        }
        return result;
    }

    private TechAiQuoteSnapshot fetchOne(String projectCode) {
        boolean backup = shouldUseBackup();
        // 降级状态时先尝试备用域；正常状态时先主域，主域失败再回退备用
        String[] order = backup
                ? new String[]{BACKUP_HOST, PRIMARY_HOST}
                : new String[]{PRIMARY_HOST, BACKUP_HOST};
        for (String host : order) {
            try {
                String body = curl(urlFor(projectCode, host));
                if (body == null || body.isBlank()) {
                    if (host.equals(PRIMARY_HOST)) recordPrimaryFailure();
                    continue;
                }
                TechAiQuoteSnapshot snap = parseBody(body, projectCode);
                if (snap != null) {
                    if (host.equals(PRIMARY_HOST)) recordPrimarySuccess();
                    return snap;
                }
                if (host.equals(PRIMARY_HOST)) recordPrimaryFailure();
            } catch (Exception e) {
                log.warn("EastMoney realtime quote failed [{} @{}]: {}", projectCode, host, e.getMessage());
                if (host.equals(PRIMARY_HOST)) recordPrimaryFailure();
            }
        }
        return null;
    }

    private boolean shouldUseBackup() {
        long firstFail = firstFailureAt.get();
        if (firstFail == 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - firstFail > FAILURE_WINDOW_MS) {
            // 冷却期到：尝试恢复主域
            firstFailureAt.set(0);
            recentFailures.set(0);
            return false;
        }
        return recentFailures.get() >= FAILURE_THRESHOLD;
    }

    private void recordPrimaryFailure() {
        if (firstFailureAt.get() == 0) {
            firstFailureAt.set(System.currentTimeMillis());
        }
        recentFailures.incrementAndGet();
    }

    private void recordPrimarySuccess() {
        if (recentFailures.get() > 0 || firstFailureAt.get() > 0) {
            recentFailures.set(0);
            firstFailureAt.set(0);
        }
    }

    private TechAiQuoteSnapshot parseBody(String body, String projectCode) {
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            if (data.isMissingNode() || data.isNull()) {
                return null;
            }
            BigDecimal latest = price(data.path("f43"));
            if (latest == null || latest.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            TechAiQuoteSnapshot quote = new TechAiQuoteSnapshot();
            quote.setStockCode(TechAiStockCodeUtils.normalizeProjectCode(data.path("f57").asText(projectCode)));
            quote.setLatestPrice(latest);
            quote.setPrevClosePrice(price(data.path("f60")));
            quote.setOpenPrice(price(data.path("f46")));
            quote.setVolume(data.path("f47").isNumber() ? data.path("f47").asLong() : null);
            quote.setAmount(decimal(data.path("f48")));
            quote.setQuoteTime(time(data.path("f86").asLong(0)));
            quote.setSource("eastmoney");
            return quote;
        } catch (Exception e) {
            log.debug("EastMoney body parse failed [{}]: {}", projectCode, e.getMessage());
            return null;
        }
    }

    private String curl(String url) throws Exception {
        Process process = new ProcessBuilder(
                "curl",
                "-fsSL",
                "--max-time", "5",
                url
        ).start();
        boolean done = process.waitFor(6, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            return null;
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException(stderr.isBlank() ? "curl exited " + process.exitValue() : stderr.trim());
        }
        return stdout;
    }

    private String urlFor(String projectCode, String host) {
        String normalized = TechAiStockCodeUtils.normalizeProjectCode(projectCode);
        int dot = normalized.indexOf('.');
        String code = normalized.substring(0, dot);
        String market = normalized.substring(dot + 1);
        String secid = ("sh".equals(market) ? "1." : "0.") + code;
        return "https://" + host + "/api/qt/stock/get"
                + "?secid=" + secid
                + "&fields=f43,f46,f47,f48,f57,f58,f60,f86";
    }

    private BigDecimal price(JsonNode node) {
        BigDecimal raw = decimal(node);
        if (raw == null || raw.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return raw.divide(BigDecimal.valueOf(100));
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return BigDecimal.valueOf(node.asDouble());
        }
        String text = node.asText("").trim();
        if (text.isBlank() || "-".equals(text)) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime time(long epochSeconds) {
        if (epochSeconds <= 0) {
            return LocalDateTime.now(CHINA_ZONE);
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), CHINA_ZONE);
    }
}