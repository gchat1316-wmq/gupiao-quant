package com.quant.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.quant.dto.monitor.MonitorAddRequest;
import com.quant.dto.monitor.MonitorFieldUpdateRequest;
import com.quant.dto.monitor.MonitorPoolItemDTO;
import com.quant.dto.monitor.MonitorRunResponse;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.service.AStockDataQuoteService;
import com.quant.service.monitor.MonitorService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一监控 REST 端点 — 前端从 monitor.html 调用。
 *
 * <p>端点： GET /api/monitor/pool[?poolType=tech_ai|potential|stock] 列出某池全部监控项 POST /api/monitor/pool
 * 添加监控 PATCH /api/monitor/pool/{code}/{poolType}/field 部分更新字段 DELETE
 * /api/monitor/pool/{code}/{poolType} 删除监控 POST /api/monitor/run[?poolType=...] 手动触发扫描 GET
 * /api/monitor/health 健康检查
 *
 * <p>兼容：原本 /api/tech-ai/* 和 /api/potential/* 仍然可用，但数据来自本服务。
 */
@Slf4j
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class MonitorController {

  private final MonitorService monitorService;
  private final InvestPositionCommonRepository posRepo;
  private final TradeStockBasicRepository basicRepo;
  private final AStockDataQuoteService quoteService;

  @GetMapping("/pool")
  public List<MonitorPoolItemDTO> pool(@RequestParam(required = false) String poolType) {
    List<InvestPositionCommon> rows =
        poolType == null ? posRepo.findAll() : posRepo.findByPoolType(poolType);
    if (rows == null || rows.isEmpty()) return List.of();

    List<String> codes = rows.stream().map(InvestPositionCommon::getStockCode).toList();
    Map<String, String> nameMap =
        basicRepo.findByStockCodeIn(codes).stream()
            .collect(
                Collectors.toMap(
                    TradeStockBasic::getStockCode, TradeStockBasic::getStockName, (a, b) -> a));

    Map<String, AStockDataQuoteService.QuoteSnapshot> qMap;
    try {
      qMap = quoteService.fetchQuotes(codes);
    } catch (Exception e) {
      log.warn("fetchQuotes 失败：{}", e.getMessage());
      qMap = Map.of();
    }

    List<MonitorPoolItemDTO> out = new ArrayList<>();
    for (InvestPositionCommon p : rows) {
      AStockDataQuoteService.QuoteSnapshot q = qMap == null ? null : qMap.get(p.getStockCode());
      BigDecimal latest = q == null ? null : q.latestPrice();
      BigDecimal dailyChange = null;
      if (q != null
          && q.latestPrice() != null
          && q.prevClosePrice() != null
          && q.prevClosePrice().compareTo(BigDecimal.ZERO) > 0) {
        dailyChange =
            q.latestPrice()
                .subtract(q.prevClosePrice())
                .divide(q.prevClosePrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
      }
      out.add(
          MonitorPoolItemDTO.from(
              p, nameMap.getOrDefault(p.getStockCode(), p.getStockCode()), latest, dailyChange));
    }
    return out;
  }

  @PostMapping("/pool")
  @Transactional
  public Map<String, Object> add(@RequestBody MonitorAddRequest req) {
    if (req.getStockCode() == null || req.getPoolType() == null) {
      throw new IllegalArgumentException("stockCode 和 poolType 必填");
    }
    InvestPositionCommon p =
        posRepo
            .findByStockCodeAndPoolType(req.getStockCode(), req.getPoolType())
            .orElseGet(
                () -> {
                  InvestPositionCommon np = new InvestPositionCommon();
                  np.setStockCode(req.getStockCode());
                  np.setPoolType(req.getPoolType());
                  np.setStatus("watching");
                  np.setMonitorMode("standard");
                  np.setServerchanTemplate("standard");
                  np.setFixedBuyEnabled(0);
                  np.setFixedSellEnabled(0);
                  np.setAtrAlertEnabled(0);
                  return np;
                });
    InvestPositionCommon saved = posRepo.save(p);
    Map<String, Object> resp = new HashMap<>();
    resp.put("ok", true);
    resp.put("stockCode", saved.getStockCode());
    resp.put("poolType", saved.getPoolType());
    return resp;
  }

  @PatchMapping("/pool/{stockCode}/{poolType}/field")
  @Transactional
  public Map<String, Object> updateField(
      @PathVariable String stockCode,
      @PathVariable String poolType,
      @RequestBody MonitorFieldUpdateRequest req) {
    InvestPositionCommon p =
        posRepo
            .findByStockCodeAndPoolType(stockCode, poolType)
            .orElseThrow(
                () -> new IllegalArgumentException("未找到股票: " + stockCode + " in " + poolType));
    applyField(p, req.getField(), req.getValue());
    posRepo.save(p);
    Map<String, Object> resp = new HashMap<>();
    resp.put("ok", true);
    resp.put("field", req.getField());
    resp.put("value", req.getValue());
    return resp;
  }

  @DeleteMapping("/pool/{stockCode}/{poolType}")
  @Transactional
  public Map<String, Object> remove(@PathVariable String stockCode, @PathVariable String poolType) {
    posRepo.findByStockCodeAndPoolType(stockCode, poolType).ifPresent(posRepo::delete);
    return Map.of("ok", true, "stockCode", stockCode, "poolType", poolType);
  }

  @PostMapping("/run")
  public MonitorRunResponse run(@RequestParam(required = false) String poolType) {
    int triggered =
        switch (poolType == null ? "" : poolType) {
          case "" ->
              monitorService.scan("stock")
                  + monitorService.scan("tech_ai")
                  + monitorService.scan("potential");
          case "stock", "tech_ai", "potential" -> monitorService.scan(poolType);
          default -> throw new IllegalArgumentException("未知 poolType: " + poolType);
        };
    return new MonitorRunResponse("scan done", triggered);
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    Map<String, Object> r = new HashMap<>();
    r.put("ok", true);
    r.put("ts", System.currentTimeMillis());
    r.put("service", "MonitorService");
    return r;
  }

  /* ─────────── helpers ─────────── */

  private void applyField(InvestPositionCommon p, String field, Object value) {
    switch (field == null ? "" : field) {
      case "fixedBuyPrice" -> p.setFixedBuyPrice(toBigDecimal(value));
      case "fixedSellPrice" -> p.setFixedSellPrice(toBigDecimal(value));
      case "fixedBuyEnabled" -> p.setFixedBuyEnabled(toInteger(value));
      case "fixedSellEnabled" -> p.setFixedSellEnabled(toInteger(value));
      case "atrAlertAmplitude" -> p.setAtrAlertAmplitude(toBigDecimal(value));
      case "atrAlertEnabled" -> p.setAtrAlertEnabled(toInteger(value));
      case "stopLossPct" -> p.setStopLossPct(toBigDecimal(value));
      case "takeProfitPct" -> p.setTakeProfitPct(toBigDecimal(value));
      case "entryPrice" -> p.setEntryPrice(toBigDecimal(value));
      case "monitorMode" -> p.setMonitorMode(value == null ? "standard" : value.toString());
      case "serverchanTemplate" ->
          p.setServerchanTemplate(value == null ? "standard" : value.toString());
      case "alertMinute1mPct" -> p.setAlertMinute1mPct(toBigDecimal(value));
      case "alertMinute5mPct" -> p.setAlertMinute5mPct(toBigDecimal(value));
      case "alertDailyPct" -> p.setAlertDailyPct(toBigDecimal(value));
      case "alertThreeDayPct" -> p.setAlertThreeDayPct(toBigDecimal(value));
      case "alertTurnoverRatioPct" -> p.setAlertTurnoverRatioPct(toBigDecimal(value));
      case "addStepPct" -> p.setAddStepPct(toBigDecimal(value));
      case "trailPct" -> p.setTrailPct(toBigDecimal(value));
      case "useAtr" -> p.setUseAtr(toInteger(value));
      case "atrPeriod" -> p.setAtrPeriod(toInteger(value));
      case "atrTrailMult" -> p.setAtrTrailMult(toBigDecimal(value));
      case "maxLots" -> p.setMaxLots(toBigDecimal(value));
      case "targetSellPrice" -> p.setTargetSellPrice(toBigDecimal(value));
      case "status" -> p.setStatus(value == null ? "watching" : value.toString());
      case "memo" -> p.setStatus(p.getStatus()); // memo 在 pool 表，单独接口
      default -> throw new IllegalArgumentException("未知字段: " + field);
    }
  }

  private static BigDecimal toBigDecimal(Object v) {
    if (v == null) return null;
    if (v instanceof Number n) return new BigDecimal(n.toString());
    return new BigDecimal(v.toString());
  }

  private static Integer toInteger(Object v) {
    if (v == null) return null;
    if (v instanceof Boolean b) return b ? 1 : 0;
    if (v instanceof Number n) return n.intValue();
    return Integer.parseInt(v.toString());
  }
}
