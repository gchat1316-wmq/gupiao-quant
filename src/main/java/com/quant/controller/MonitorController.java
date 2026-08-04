package com.quant.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.quant.dto.monitor.MonitorAddRequest;
import com.quant.dto.monitor.MonitorBatchAddResponse;
import com.quant.dto.monitor.MonitorFieldUpdateRequest;
import com.quant.dto.monitor.MonitorPoolItemDTO;
import com.quant.dto.monitor.MonitorRunResponse;
import com.quant.entity.InvestAlert;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;
import com.quant.service.monitor.MonitorService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一监控 REST 端点 — 前端从 monitor.html 调用。
 *
 * <p>端点： GET /api/monitor/pool[?poolType=invest|tech_ai|potential] 列出某池全部监控项 POST /api/monitor/pool
 * 添加监控（支持批量） PATCH /api/monitor/pool/{code}/{poolType}/field 部分更新字段 DELETE
 * /api/monitor/pool/{code}/{poolType} 删除监控 POST /api/monitor/run[?poolType=...] 手动触发扫描 GET
 * /api/monitor/alerts 最近告警 GET /api/monitor/health 健康检查
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
  private final InvestAlertRepository alertRepo;

  @GetMapping("/pool")
  public List<MonitorPoolItemDTO> pool(@RequestParam(required = false) String poolType) {
    List<InvestPositionCommon> rows;
    if (poolType == null || poolType.isBlank() || "all".equalsIgnoreCase(poolType)) {
      rows = posRepo.findAll();
    } else {
      rows = posRepo.findByPoolType(MonitorService.normalizePoolType(poolType));
    }
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
  public MonitorBatchAddResponse add(@RequestBody MonitorAddRequest req) {
    if (req.getPoolType() == null || req.getPoolType().isBlank()) {
      throw new IllegalArgumentException("poolType 必填");
    }
    String poolType = MonitorService.normalizePoolType(req.getPoolType());
    if (!MonitorService.isKnownPoolType(poolType)) {
      throw new IllegalArgumentException("未知 poolType: " + req.getPoolType());
    }

    List<String> codes = parseCodes(req);
    if (codes.isEmpty()) {
      throw new IllegalArgumentException("stockCode / stockCodes 至少提供一个");
    }

    MonitorBatchAddResponse resp = new MonitorBatchAddResponse();
    for (String code : codes) {
      MonitorBatchAddResponse.Item item = new MonitorBatchAddResponse.Item();
      item.setStockCode(code);
      item.setPoolType(poolType);
      try {
        var existing = posRepo.findByStockCodeAndPoolType(code, poolType);
        if (existing.isPresent()) {
          InvestPositionCommon p = existing.get();
          applyOptionalDefaults(p, req);
          posRepo.save(p);
          item.setStatus("exists");
          item.setMessage("已存在，已更新可选字段");
          resp.setSkipped(resp.getSkipped() + 1);
        } else {
          InvestPositionCommon np = newBlank(code, poolType);
          applyOptionalDefaults(np, req);
          posRepo.save(np);
          item.setStatus("added");
          item.setMessage("ok");
          resp.setAdded(resp.getAdded() + 1);
        }
      } catch (Exception e) {
        item.setStatus("failed");
        item.setMessage(e.getMessage());
        resp.setFailed(resp.getFailed() + 1);
        resp.setOk(false);
      }
      resp.getItems().add(item);
    }
    return resp;
  }

  @PatchMapping("/pool/{stockCode}/{poolType}/field")
  @Transactional
  public Map<String, Object> updateField(
      @PathVariable String stockCode,
      @PathVariable String poolType,
      @RequestBody MonitorFieldUpdateRequest req) {
    String normalized = MonitorService.normalizePoolType(poolType);
    InvestPositionCommon p =
        posRepo
            .findByStockCodeAndPoolType(stockCode, normalized)
            .orElseThrow(
                () -> new IllegalArgumentException("未找到股票: " + stockCode + " in " + normalized));
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
    String normalized = MonitorService.normalizePoolType(poolType);
    posRepo.findByStockCodeAndPoolType(stockCode, normalized).ifPresent(posRepo::delete);
    return Map.of("ok", true, "stockCode", stockCode, "poolType", normalized);
  }

  @PostMapping("/run")
  public MonitorRunResponse run(@RequestParam(required = false) String poolType) {
    String key =
        poolType == null || poolType.isBlank() || "all".equalsIgnoreCase(poolType)
            ? ""
            : MonitorService.normalizePoolType(poolType);
    int triggered =
        switch (key) {
          case "" ->
              monitorService.scan(MonitorService.POOL_INVEST)
                  + monitorService.scan(MonitorService.POOL_TECH_AI)
                  + monitorService.scan(MonitorService.POOL_POTENTIAL);
          case MonitorService.POOL_INVEST,
                  MonitorService.POOL_TECH_AI,
                  MonitorService.POOL_POTENTIAL ->
              monitorService.scan(key);
          default -> throw new IllegalArgumentException("未知 poolType: " + poolType);
        };
    return new MonitorRunResponse("scan done", triggered);
  }

  @GetMapping("/alerts")
  public List<Map<String, Object>> alerts() {
    List<InvestAlert> rows = alertRepo.findTop50ByOrderByTriggerAtDesc();
    List<Map<String, Object>> out = new ArrayList<>();
    for (InvestAlert a : rows) {
      Map<String, Object> m = new HashMap<>();
      m.put("id", a.getId());
      m.put("stockCode", a.getStockCode());
      m.put("signalType", a.getSignalType());
      m.put("title", a.getTitle());
      m.put("triggerPrice", a.getTriggerPrice());
      m.put("triggerAt", a.getTriggerAt());
      m.put("pushed", a.getPushed());
      out.add(m);
    }
    return out;
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

  private static InvestPositionCommon newBlank(String code, String poolType) {
    InvestPositionCommon np = new InvestPositionCommon();
    np.setStockCode(code);
    np.setPoolType(poolType);
    np.setStatus("watching");
    np.setMonitorMode("standard");
    np.setServerchanTemplate("standard");
    np.setFixedBuyEnabled(0);
    np.setFixedSellEnabled(0);
    np.setAtrAlertEnabled(0);
    np.setAlertState("none");
    np.setPositionState("none");
    np.setPositionLots(BigDecimal.ZERO);
    np.setUseAtr(0);
    return np;
  }

  private static void applyOptionalDefaults(InvestPositionCommon p, MonitorAddRequest req) {
    if (req.getFixedBuyPrice() != null) p.setFixedBuyPrice(req.getFixedBuyPrice());
    if (req.getFixedSellPrice() != null) p.setFixedSellPrice(req.getFixedSellPrice());
    if (req.getFixedBuyEnabled() != null) p.setFixedBuyEnabled(req.getFixedBuyEnabled());
    if (req.getFixedSellEnabled() != null) p.setFixedSellEnabled(req.getFixedSellEnabled());
    if (req.getEntryPrice() != null) p.setEntryPrice(req.getEntryPrice());
    if (req.getTakeProfitPct() != null) p.setTakeProfitPct(req.getTakeProfitPct());
    if (req.getStopLossPct() != null) p.setStopLossPct(req.getStopLossPct());
    if (req.getMonitorMode() != null && !req.getMonitorMode().isBlank()) {
      p.setMonitorMode(req.getMonitorMode());
    }
    if (req.getServerchanTemplate() != null && !req.getServerchanTemplate().isBlank()) {
      p.setServerchanTemplate(req.getServerchanTemplate());
    }
  }

  static List<String> parseCodes(MonitorAddRequest req) {
    Set<String> out = new LinkedHashSet<>();
    if (req.getStockCodes() != null) {
      for (String c : req.getStockCodes()) {
        addNormalizedCode(out, c);
      }
    }
    if (req.getStockCode() != null && !req.getStockCode().isBlank()) {
      String raw = req.getStockCode().replace('，', ',').replace('；', ';').replace('、', ',');
      for (String part : raw.split("[,;\\s\\n\\r\\t]+")) {
        addNormalizedCode(out, part);
      }
    }
    return new ArrayList<>(out);
  }

  private static void addNormalizedCode(Set<String> out, String raw) {
    if (raw == null) return;
    String code = raw.trim().toUpperCase(Locale.ROOT);
    if (code.isEmpty()) return;
    // 允许 600519 / 600519.SH / sh600519
    if (code.matches("(?i)(sh|sz|bj)\\d{6}")) {
      String mkt = code.substring(0, 2).toUpperCase(Locale.ROOT);
      String num = code.substring(2);
      code = num + "." + mkt;
    } else if (code.matches("\\d{6}")) {
      // 粗略推断交易所：6/9→SH，0/3→SZ，4/8→BJ
      char c0 = code.charAt(0);
      String mkt = (c0 == '6' || c0 == '9') ? "SH" : (c0 == '4' || c0 == '8') ? "BJ" : "SZ";
      code = code + "." + mkt;
    }
    out.add(code);
  }

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
      default -> throw new IllegalArgumentException("未知字段: " + field);
    }
  }

  private static BigDecimal toBigDecimal(Object v) {
    if (v == null || "".equals(v)) return null;
    if (v instanceof Number n) return new BigDecimal(n.toString());
    String s = v.toString().trim();
    if (s.isEmpty()) return null;
    return new BigDecimal(s);
  }

  private static Integer toInteger(Object v) {
    if (v == null || "".equals(v)) return null;
    if (v instanceof Boolean b) return b ? 1 : 0;
    if (v instanceof Number n) return n.intValue();
    return Integer.parseInt(v.toString());
  }
}
