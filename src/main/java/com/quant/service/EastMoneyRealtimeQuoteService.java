package com.quant.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.service.techai.TechAiStockCodeUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 东方财富实时行情拉取。
 *
 * <p>2026-06-29 性能重构：
 *
 * <ul>
 *   <li>ProcessBuilder("curl") → 共享 Java HttpClient，省 10–30ms/次 冷启动
 *   <li>串行 fetch → 共享线程池并发，对 200 只股票 30s+ → ~1–2s
 * </ul>
 *
 * <p>暂未上 secid 批量 URL（拼多只一次请求）：该接口响应格式社区说法不一，等下个迭代实测后再上。
 */
@Slf4j
@Service
public class EastMoneyRealtimeQuoteService {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

  private static final String PRIMARY_HOST = "push2.eastmoney.com";
  private static final String BACKUP_HOST = "push2delay.eastmoney.com";

  private static final long FAILURE_WINDOW_MS = 30_000L;
  private static final int FAILURE_THRESHOLD = 3;
  private final java.util.concurrent.atomic.AtomicLong firstFailureAt =
      new java.util.concurrent.atomic.AtomicLong(0);
  private final java.util.concurrent.atomic.AtomicInteger recentFailures =
      new java.util.concurrent.atomic.AtomicInteger(0);

  private final QuoteHttpClient quoteHttpClient;

  public EastMoneyRealtimeQuoteService(QuoteHttpClient quoteHttpClient) {
    this.quoteHttpClient = quoteHttpClient;
  }

  public Map<String, TechAiQuoteSnapshot> fetch(Collection<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return Map.of();
    }
    // 去重 + 并发拉取
    List<String> uniqueCodes = codes.stream().distinct().toList();
    List<CompletableFuture<SnapshotEntry>> futures = new ArrayList<>(uniqueCodes.size());
    for (String code : uniqueCodes) {
      futures.add(
          CompletableFuture.supplyAsync(() -> fetchOneToEntry(code), quoteHttpClient.executor()));
    }
    Map<String, TechAiQuoteSnapshot> result = new ConcurrentHashMap<>();
    for (CompletableFuture<SnapshotEntry> f : futures) {
      SnapshotEntry entry = f.join();
      if (entry != null && entry.snapshot != null && entry.snapshot.getLatestPrice() != null) {
        result.put(entry.key, entry.snapshot);
      }
    }
    return result;
  }

  /** 包装 (normalizedCode, snapshot)，便于并发 join 后聚合。 */
  private record SnapshotEntry(String key, TechAiQuoteSnapshot snapshot) {}

  private SnapshotEntry fetchOneToEntry(String projectCode) {
    TechAiQuoteSnapshot snap = fetchOne(projectCode);
    if (snap == null) return null;
    return new SnapshotEntry(TechAiStockCodeUtils.normalizeProjectCode(snap.getStockCode()), snap);
  }

  private TechAiQuoteSnapshot fetchOne(String projectCode) {
    boolean backup = shouldUseBackup();
    String[] order =
        backup
            ? new String[] {BACKUP_HOST, PRIMARY_HOST}
            : new String[] {PRIMARY_HOST, BACKUP_HOST};
    for (String host : order) {
      String body = quoteHttpClient.getUtf8(urlFor(projectCode, host));
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
      quote.setStockCode(
          TechAiStockCodeUtils.normalizeProjectCode(data.path("f57").asText(projectCode)));
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

  private String urlFor(String projectCode, String host) {
    String normalized = TechAiStockCodeUtils.normalizeProjectCode(projectCode);
    int dot = normalized.indexOf('.');
    String code = normalized.substring(0, dot);
    String market = normalized.substring(dot + 1);
    String secid = ("sh".equals(market) ? "1." : "0.") + code;
    return "https://"
        + host
        + "/api/qt/stock/get"
        + "?secid="
        + secid
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
