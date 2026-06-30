package com.quant.service;

import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.service.techai.TechAiStockCodeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 新浪实时行情拉取（仅作为东方财富 fallback 使用）。
 *
 * <p>2026-06-29 性能重构：
 * <ul>
 *   <li>ProcessBuilder("curl") → 共享 Java HttpClient</li>
 *   <li>逐只 URL → 新浪批量 URL（list=sh600000,sz000001, ...，一次最多 50/批）</li>
 *   <li>多批并发</li>
 * </ul>
 */
@Slf4j
@Service
public class SinaRealtimeQuoteService {

    private static final Charset GBK = Charset.forName("GBK");
    private static final int BATCH_SIZE = 50;
    private static final String BATCH_URL_PREFIX = "https://hq.sinajs.cn/list=";
    private static final String REFERER = "https://finance.sina.com.cn";

    private final QuoteHttpClient quoteHttpClient;

    public SinaRealtimeQuoteService(QuoteHttpClient quoteHttpClient) {
        this.quoteHttpClient = quoteHttpClient;
    }

    public Map<String, TechAiQuoteSnapshot> fetch(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Map.of();
        }
        List<String> unique = codes.stream().distinct().toList();
        List<List<String>> batches = partition(unique, BATCH_SIZE);
        List<CompletableFuture<Map<String, TechAiQuoteSnapshot>>> futures = new ArrayList<>(batches.size());
        for (List<String> batch : batches) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> fetchBatch(batch), quoteHttpClient.executor()));
        }
        Map<String, TechAiQuoteSnapshot> result = new ConcurrentHashMap<>();
        for (CompletableFuture<Map<String, TechAiQuoteSnapshot>> f : futures) {
            result.putAll(f.join());
        }
        return result;
    }

    private Map<String, TechAiQuoteSnapshot> fetchBatch(List<String> projectCodes) {
        Map<String, TechAiQuoteSnapshot> result = new HashMap<>();
        if (projectCodes.isEmpty()) return result;

        // 拼批量 URL: list=sh600000,sz000001,...
        String sinaCodes = projectCodes.stream()
                .map(this::toSinaCode)
                .collect(Collectors.joining(","));
        String body = quoteHttpClient.getWithReferer(BATCH_URL_PREFIX + sinaCodes, REFERER, GBK);
        if (body == null || body.isBlank()) {
            return result;
        }

        // 响应：每行 var hq_str_<code>="字段1,字段2,..."; 按 codes 顺序对应
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length && i < projectCodes.size(); i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            TechAiQuoteSnapshot quote = parseLine(line, projectCodes.get(i));
            if (quote != null && quote.getLatestPrice() != null) {
                String key = TechAiStockCodeUtils.normalizeProjectCode(quote.getStockCode());
                result.put(key, quote);
            }
        }
        return result;
    }

    /** 000001.SZ -> sz000001 */
    private String toSinaCode(String projectCode) {
        String normalized = TechAiStockCodeUtils.normalizeProjectCode(projectCode);
        int dot = normalized.indexOf('.');
        if (dot < 0) return normalized;
        return normalized.substring(dot + 1) + normalized.substring(0, dot);
    }

    private TechAiQuoteSnapshot parseLine(String line, String originalProjectCode) {
        try {
            int first = line.indexOf('"');
            int last = line.lastIndexOf('"');
            if (first < 0 || last <= first) return null;
            String[] parts = line.substring(first + 1, last).split(",", -1);
            if (parts.length < 32) return null;
            BigDecimal latest = decimal(parts[3]);
            if (latest == null || latest.compareTo(BigDecimal.ZERO) <= 0) return null;
            TechAiQuoteSnapshot quote = new TechAiQuoteSnapshot();
            quote.setStockCode(TechAiStockCodeUtils.normalizeProjectCode(originalProjectCode));
            quote.setOpenPrice(decimal(parts[1]));
            quote.setPrevClosePrice(decimal(parts[2]));
            quote.setLatestPrice(latest);
            quote.setVolume(longValue(parts[8]));
            quote.setAmount(decimal(parts[9]));
            quote.setQuoteTime(LocalDateTime.parse(parts[30] + "T" + parts[31]));
            quote.setSource("sina");
            return quote;
        } catch (Exception e) {
            log.debug("Sina realtime quote failed [{}]: {}", originalProjectCode, e.getMessage());
            return null;
        }
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>((list.size() + size - 1) / size);
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    private BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longValue(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}