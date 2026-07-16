package com.quant.service.aistockdata;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 实时行情共享 HTTP 客户端。
 *
 * <p>替换原 EastMoney/Sina 服务里的 {@code ProcessBuilder("curl", ...)}：每次冷启子进程 10–30ms， 200 只股票光冷启就浪费
 * 2–6s。改用 Java 内置 {@link HttpClient} + 共享连接池后，开销降到 1–5ms/次。
 *
 * <p>线程池用 {@link Executors#newFixedThreadPool}，避免虚拟线程在 Tomcat 工作线程上嵌套死锁； 池大小由 {@code
 * quote.http.threads} 控制（默认 16，对东方财富保守避免限流）。
 */
@Slf4j
@Component
public class QuoteHttpClient {

  private final HttpClient httpClient;
  private final ExecutorService executor;
  private final Duration requestTimeout;

  public QuoteHttpClient(
      @Value("${quote.http.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${quote.http.request-timeout-ms:3000}") int requestTimeoutMs,
      @Value("${quote.http.threads:16}") int threads) {
    this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    AtomicLong seq = new AtomicLong();
    ThreadFactory tf =
        r -> {
          Thread t = new Thread(r, "quote-http-" + seq.incrementAndGet());
          t.setDaemon(true);
          return t;
        };
    this.executor = Executors.newFixedThreadPool(Math.max(1, threads), tf);
    log.info(
        "QuoteHttpClient 初始化: connectTimeout={}ms requestTimeout={}ms threads={}",
        connectTimeoutMs,
        requestTimeoutMs,
        threads);
  }

  public ExecutorService executor() {
    return executor;
  }

  /** 简单 GET，返回 UTF-8 文本。失败/超时返回 null，异常不抛出（由调用方记录）。 */
  public String getUtf8(String url) {
    return get(url, StandardCharsets.UTF_8);
  }

  public String get(String url, Charset charset) {
    HttpRequest req =
        HttpRequest.newBuilder().uri(URI.create(url)).timeout(requestTimeout).GET().build();
    try {
      HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (resp.statusCode() / 100 != 2) {
        log.debug("Quote HTTP {} -> {}", resp.statusCode(), url);
        return null;
      }
      return new String(resp.body(), charset);
    } catch (Exception e) {
      log.debug("Quote HTTP failed for {}: {}", url, e.getMessage());
      return null;
    }
  }

  /** 带 Referer 的 GET（新浪必需，否则 403）。 */
  public String getWithReferer(String url, String referer, Charset charset) {
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("Referer", referer)
            .GET()
            .build();
    try {
      HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (resp.statusCode() / 100 != 2) {
        log.debug("Quote HTTP {} -> {}", resp.statusCode(), url);
        return null;
      }
      return new String(resp.body(), charset);
    } catch (Exception e) {
      log.debug("Quote HTTP failed for {}: {}", url, e.getMessage());
      return null;
    }
  }
}
