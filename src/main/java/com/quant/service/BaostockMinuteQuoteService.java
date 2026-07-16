package com.quant.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.entity.TechAiQuoteSnapshot;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class BaostockMinuteQuoteService {

  private static final Duration CACHE_TTL = Duration.ofSeconds(60);

  private final ObjectMapper objectMapper;
  private final Map<String, CachedQuote> cache = new ConcurrentHashMap<>();

  public BaostockMinuteQuoteService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Map<String, TechAiQuoteSnapshot> fetchLatest5m(Collection<String> codes) {
    Map<String, TechAiQuoteSnapshot> result = new HashMap<>();
    LocalDateTime now = LocalDateTime.now();
    List<String> missing =
        codes.stream()
            .map(String::trim)
            .filter(code -> !code.isBlank())
            .distinct()
            .filter(
                code -> {
                  CachedQuote cached = cache.get(code);
                  if (cached != null && cached.fetchedAt.plus(CACHE_TTL).isAfter(now)) {
                    if (cached.quote != null) {
                      result.put(code, cached.quote);
                    }
                    return false;
                  }
                  return true;
                })
            .toList();
    if (missing.isEmpty()) {
      return result;
    }

    try {
      ProcessBuilder pb = new ProcessBuilder(command(missing));
      pb.directory(Path.of("").toAbsolutePath().toFile());
      Process process = pb.start();
      boolean done = process.waitFor(20, TimeUnit.SECONDS);
      if (!done) {
        process.destroyForcibly();
        rememberMisses(missing);
        return result;
      }
      String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        log.warn("BaoStock 5m quote failed: {}", stderr.isBlank() ? stdout : stderr);
        rememberMisses(missing);
        return result;
      }
      for (BaostockQuote row :
          objectMapper.readValue(stdout, new TypeReference<List<BaostockQuote>>() {})) {
        TechAiQuoteSnapshot quote = toSnapshot(row);
        result.put(quote.getStockCode(), quote);
        cache.put(quote.getStockCode(), new CachedQuote(quote, now));
      }
      for (String code : missing) {
        cache.putIfAbsent(code, new CachedQuote(null, now));
      }
    } catch (IOException e) {
      log.warn("BaoStock 5m quote unavailable: {}", e.getMessage());
      rememberMisses(missing);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      rememberMisses(missing);
    }
    return result;
  }

  private List<String> command(List<String> codes) {
    List<String> command = new java.util.ArrayList<>();
    command.add("python3");
    command.add("scripts/baostock_latest_5m.py");
    command.addAll(codes);
    return command;
  }

  private void rememberMisses(List<String> codes) {
    LocalDateTime now = LocalDateTime.now();
    for (String code : codes) {
      cache.put(code, new CachedQuote(null, now));
    }
  }

  private TechAiQuoteSnapshot toSnapshot(BaostockQuote row) {
    TechAiQuoteSnapshot quote = new TechAiQuoteSnapshot();
    quote.setStockCode(row.stockCode);
    quote.setQuoteTime(row.quoteTime == null ? LocalDateTime.now() : row.quoteTime);
    quote.setLatestPrice(row.latestPrice);
    quote.setPrevClosePrice(row.prevClosePrice);
    quote.setOpenPrice(row.openPrice);
    quote.setVolume(row.volume);
    quote.setAmount(row.amount);
    quote.setTurnoverRate(row.turnoverRate);
    quote.setMinute5OpenPrice(row.minute5OpenPrice);
    quote.setMinute5Time(row.minute5Time);
    quote.setSource("baostock");
    return quote;
  }

  private record CachedQuote(TechAiQuoteSnapshot quote, LocalDateTime fetchedAt) {}

  @Getter
  @Setter
  private static class BaostockQuote {
    private String stockCode;
    private LocalDateTime quoteTime;
    private BigDecimal latestPrice;
    private BigDecimal prevClosePrice;
    private BigDecimal openPrice;
    private Long volume;
    private BigDecimal amount;
    private BigDecimal turnoverRate;
    private BigDecimal minute5OpenPrice;
    private LocalDateTime minute5Time;
  }
}
