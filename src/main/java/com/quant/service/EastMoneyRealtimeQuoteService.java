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

@Slf4j
@Service
public class EastMoneyRealtimeQuoteService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

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
        try {
            String body = curl(url(projectCode));
            if (body == null || body.isBlank()) {
                return null;
            }
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
            log.warn("EastMoney realtime quote failed [{}]: {}", projectCode, e.getMessage());
            return null;
        }
    }

    private String curl(String url) throws Exception {
        Process process = new ProcessBuilder(
                "curl",
                "-fsSL",
                "--max-time", "6",
                url
        ).start();
        boolean done = process.waitFor(7, TimeUnit.SECONDS);
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

    private String url(String projectCode) {
        String normalized = TechAiStockCodeUtils.normalizeProjectCode(projectCode);
        int dot = normalized.indexOf('.');
        String code = normalized.substring(0, dot);
        String market = normalized.substring(dot + 1);
        String secid = ("sh".equals(market) ? "1." : "0.") + code;
        return "https://push2.eastmoney.com/api/qt/stock/get"
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
