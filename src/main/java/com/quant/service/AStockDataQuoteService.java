package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.service.techai.TechAiStockCodeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AStockDataQuoteService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Charset GBK = Charset.forName("GBK");
    private static final DateTimeFormatter QUOTE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int QUOTE_BATCH_SIZE = 60;

    private final Map<String, CachedYearStartClose> yearStartCloseCache = new ConcurrentHashMap<>();

    public Map<String, QuoteSnapshot> fetchQuotes(Collection<String> codes) {
        List<String> normalized = normalizeCodes(codes);
        if (normalized.isEmpty()) {
            return Map.of();
        }
        Map<String, QuoteSnapshot> result = new HashMap<>();
        for (int i = 0; i < normalized.size(); i += QUOTE_BATCH_SIZE) {
            List<String> batch = normalized.subList(i, Math.min(i + QUOTE_BATCH_SIZE, normalized.size()));
            String url = "https://qt.gtimg.cn/q=" + batch.stream()
                    .map(this::toTencentCode)
                    .collect(Collectors.joining(","));
            try {
                String body = httpGet(url, GBK, "https://stockapp.finance.qq.com/");
                parseQuoteBody(body, result);
            } catch (Exception e) {
                log.warn("a-stock-data/tencent quote fetch failed for batch {}: {}", batch, e.getMessage());
            }
        }
        return result;
    }

    public Map<String, BigDecimal> fetchYearStartCloses(Collection<String> codes, LocalDate yearStart) {
        List<String> normalized = normalizeCodes(codes);
        if (normalized.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> result = new ConcurrentHashMap<>();
        normalized.parallelStream().forEach(code -> {
            BigDecimal close = fetchYearStartClose(code, yearStart);
            if (close != null) {
                result.put(normalizeKey(code), close);
            }
        });
        return result;
    }

    private BigDecimal fetchYearStartClose(String code, LocalDate yearStart) {
        String normalizedCode = TechAiStockCodeUtils.normalizeProjectCode(code);
        String cacheKey = normalizedCode + "@" + yearStart.getYear();
        CachedYearStartClose cached = yearStartCloseCache.get(cacheKey);
        if (cached != null && LocalDate.now().equals(cached.fetchedDate())) {
            return cached.closePrice();
        }
        String tencentCode = toTencentCode(normalizedCode);
        String url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param="
                + tencentCode + ",day,,," + daysBack(yearStart) + ",qfq";
        try {
            String body = httpGet(url, StandardCharsets.UTF_8, "https://stockapp.finance.qq.com/");
            BigDecimal close = parseYearStartClose(body, tencentCode, yearStart);
            if (close != null) {
                yearStartCloseCache.put(cacheKey, new CachedYearStartClose(close, LocalDate.now()));
            }
            return close;
        } catch (Exception e) {
            log.warn("a-stock-data/tencent qfq kline failed [{}]: {}", normalizedCode, e.getMessage());
            return null;
        }
    }

    private int daysBack(LocalDate yearStart) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(yearStart.minusDays(5), LocalDate.now());
        return (int) Math.max(120, days + 30);
    }

    private void parseQuoteBody(String body, Map<String, QuoteSnapshot> out) {
        if (body == null || body.isBlank()) {
            return;
        }
        for (String line : body.split(";")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("v_")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 2) {
                continue;
            }
            String rawCode = trimmed.substring(2, eq);
            String payload = trimmed.substring(eq + 1).trim();
            if (payload.startsWith("\"")) {
                payload = payload.substring(1);
            }
            if (payload.endsWith("\"")) {
                payload = payload.substring(0, payload.length() - 1);
            }
            String[] parts = payload.split("~", -1);
            if (parts.length < 46) {
                continue;
            }
            String projectCode = fromTencentCode(rawCode);
            BigDecimal latestPrice = decimal(parts[3]);
            if (latestPrice == null) {
                continue;
            }
            QuoteSnapshot snapshot = new QuoteSnapshot(
                    projectCode.toUpperCase(),
                    latestPrice,
                    decimal(parts[4]),
                    decimal(parts[45]),
                    parseQuoteTime(parts[30]),
                    "a-stock-data/tencent"
            );
            out.put(normalizeKey(snapshot.stockCode()), snapshot);
        }
    }

    private BigDecimal parseYearStartClose(String body, String tencentCode, LocalDate yearStart) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode stockNode = root.path("data").path(tencentCode);
        JsonNode rows = stockNode.path("qfqday");
        if (!rows.isArray()) {
            rows = stockNode.path("day");
        }
        if (!rows.isArray()) {
            return null;
        }
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 3) {
                continue;
            }
            LocalDate tradeDate = parseDate(row.get(0).asText());
            if (tradeDate == null || tradeDate.isBefore(yearStart)) {
                continue;
            }
            return decimal(row.get(2).asText());
        }
        return null;
    }

    private String httpGet(String url, Charset charset, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        if (referer != null && !referer.isBlank()) {
            conn.setRequestProperty("Referer", referer);
        }
        conn.connect();
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), charset);
        if (status >= 400) {
            throw new IllegalStateException("HTTP " + status + ": " + body);
        }
        return body;
    }

    private List<String> normalizeCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(codes.stream()
                .map(TechAiStockCodeUtils::normalizeProjectCode)
                .filter(code -> !code.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private String toTencentCode(String projectCode) {
        String normalized = TechAiStockCodeUtils.normalizeProjectCode(projectCode);
        int dot = normalized.indexOf('.');
        if (dot < 0) {
            return normalized.toLowerCase();
        }
        return normalized.substring(dot + 1).toLowerCase() + normalized.substring(0, dot);
    }

    private String fromTencentCode(String rawCode) {
        if (rawCode == null || rawCode.length() < 8) {
            return TechAiStockCodeUtils.normalizeProjectCode(rawCode);
        }
        String market = rawCode.substring(0, 2);
        String code = rawCode.substring(2);
        return TechAiStockCodeUtils.normalizeProjectCode(code + "." + market);
    }

    private String normalizeKey(String code) {
        return TechAiStockCodeUtils.normalizeProjectCode(code).toUpperCase();
    }

    private BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime parseQuoteTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), QUOTE_TIME_FORMATTER);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    public record QuoteSnapshot(
            String stockCode,
            BigDecimal latestPrice,
            BigDecimal prevClosePrice,
            BigDecimal totalMarketCapYi,
            LocalDateTime quoteTime,
            String source
    ) {
    }

    private record CachedYearStartClose(BigDecimal closePrice, LocalDate fetchedDate) {
    }
}
