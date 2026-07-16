package com.quant.controller;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.service.recap.FundFlowService;
import com.quant.service.recap.FundFlowService.FundFlowData;

import lombok.extern.slf4j.Slf4j;

/**
 * 板块资金流 REST + SSE 端点。
 *
 * <p>GET /api/fund-flow — 当前缓存数据（JSON）
 *
 * <p>GET /api/fund-flow/stream — SSE 实时推送流，每 15s 推送一次
 *
 * <p>GET /api/fund-flow/refresh — 强制从东方财富刷新
 */
@Slf4j
@RestController
@RequestMapping("/api/fund-flow")
public class FundFlowController {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long SSE_TIMEOUT = 30 * 60 * 1000L; // 30min
  private static final long PUSH_INTERVAL_SEC = 15;

  private final FundFlowService fundFlowService;
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  public FundFlowController(FundFlowService fundFlowService) {
    this.fundFlowService = fundFlowService;
  }

  /** 当前缓存数据 */
  @GetMapping
  public FundFlowData fundFlow() {
    return fundFlowService.getCached();
  }

  /** 强制刷新 */
  @GetMapping("/refresh")
  public FundFlowData refresh() {
    return fundFlowService.refresh();
  }

  /**
   * SSE 实时推送流。
   *
   * <p>浏览器端订阅方式：
   *
   * <pre>
   * const es = new EventSource('/gp/api/fund-flow/stream');
   * es.onmessage = e => {
   *   const data = JSON.parse(e.data);
   *   render(data.items);
   * };
   * </pre>
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
    AtomicBoolean closed = new AtomicBoolean(false);

    // 首次立即推送
    pushOnce(emitter, closed);

    // 每 PUSH_INTERVAL_SEC 秒推送一次
    scheduler.scheduleAtFixedRate(
        () -> {
          if (closed.get()) return;
          pushOnce(emitter, closed);
        },
        PUSH_INTERVAL_SEC,
        PUSH_INTERVAL_SEC,
        TimeUnit.SECONDS);

    emitter.onCompletion(() -> closed.set(true));
    emitter.onTimeout(() -> closed.set(true));
    emitter.onError(
        e -> {
          closed.set(true);
          log.debug("SSE 断开: {}", e.getMessage());
        });

    return emitter;
  }

  private void pushOnce(SseEmitter emitter, AtomicBoolean closed) {
    try {
      FundFlowData data = fundFlowService.getCached();
      String json = MAPPER.writeValueAsString(data);
      emitter.send(SseEmitter.event().name("fund-flow").data(json));
    } catch (IOException e) {
      closed.set(true);
      try {
        emitter.completeWithError(e);
      } catch (Exception ignored) {
      }
    }
  }
}
