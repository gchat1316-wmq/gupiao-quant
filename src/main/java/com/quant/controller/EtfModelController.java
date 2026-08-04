package com.quant.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.quant.config.EtfModelProperties;
import com.quant.dto.etfmodel.EtfConfigRequest;
import com.quant.dto.etfmodel.EtfPoolRequest;
import com.quant.dto.etfmodel.EtfTradeRequest;
import com.quant.dto.etfmodel.EtfTradeResult;
import com.quant.entity.EtfModelConfig;
import com.quant.entity.EtfPool;
import com.quant.entity.EtfTrade;
import com.quant.repository.EtfAlertRepository;
import com.quant.repository.EtfModelConfigRepository;
import com.quant.repository.EtfNavSnapshotRepository;
import com.quant.repository.EtfPoolRepository;
import com.quant.repository.EtfTradeRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;
import com.quant.service.etfmodel.EtfKlineService;
import com.quant.service.etfmodel.EtfModelService;
import com.quant.service.etfmodel.EtfMonitorJob;
import com.quant.service.etfmodel.EtfPositionView;
import com.quant.service.etfmodel.EtfSignalEngine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 省心 ETF 交易系统 REST 端点（仅管理员使用）。
 *
 * <p>GET /api/etf-model/overview 持仓总览；/pool /trades /config /alerts /nav CRUD；POST /run 手动扫描。
 */
@Slf4j
@RestController
@RequestMapping("/api/etf-model")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class EtfModelController {

  private final EtfModelService modelService;
  private final EtfKlineService klineService;
  private final EtfSignalEngine engine;
  private final EtfMonitorJob monitorJob;
  private final EtfModelProperties props;
  private final EtfPoolRepository poolRepo;
  private final EtfTradeRepository tradeRepo;
  private final EtfModelConfigRepository configRepo;
  private final EtfNavSnapshotRepository navRepo;
  private final EtfAlertRepository alertRepo;
  private final AStockDataQuoteService quoteService;

  /* ─────────── 总览 ─────────── */

  @GetMapping("/overview")
  public Map<String, Object> overview() {
    EtfModelConfig cfg = modelService.config();
    List<EtfPositionView> positions = modelService.activePositions();

    Map<String, AStockDataQuoteService.QuoteSnapshot> quotes;
    try {
      quotes =
          quoteService.fetchQuotes(positions.stream().map(EtfPositionView::getStockCode).toList());
    } catch (Exception e) {
      log.warn("etf overview 拉取行情失败: {}", e.getMessage());
      quotes = Map.of();
    }

    BigDecimal marketValue = BigDecimal.ZERO;
    BigDecimal invested = BigDecimal.ZERO;
    List<Map<String, Object>> rows = new ArrayList<>();
    for (EtfPositionView pos : positions) {
      AStockDataQuoteService.QuoteSnapshot q = quotes.get(pos.getStockCode());
      EtfKlineService.MaSnapshot ma = klineService.maSnapshot(pos.getStockCode());
      BigDecimal latest = q != null ? q.latestPrice() : ma.latestClose();

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("poolId", pos.getPoolId());
      row.put("stockCode", pos.getStockCode());
      row.put("stockName", pos.getStockName());
      row.put("category", pos.getCategory());
      row.put("shares", pos.getShares());
      row.put("netInvested", pos.getNetInvested());
      row.put("dilutedCost", pos.getDilutedCost());
      row.put("batchesUsed", pos.getBatchesUsed());
      row.put("tp1Done", pos.isTp1Done());
      row.put("tp2Done", pos.isTp2Done());
      row.put("sl1Done", pos.isSl1Done());
      row.put("sl2Done", pos.isSl2Done());
      row.put("recoupStatus", pos.getRecoupStatus());
      row.put("latestPrice", latest);
      row.put("dailyChangePct", dailyChangePct(q));
      row.put("profitPct", pos.profitPct(latest));
      row.put("ma5", ma.ma5());
      row.put("ma20", ma.ma20());
      row.put("rise20Pct", ma.rise20Pct());

      BigDecimal mv = null;
      if (latest != null && pos.getShares() > 0) {
        mv = latest.multiply(BigDecimal.valueOf(pos.getShares())).setScale(2, RoundingMode.HALF_UP);
        marketValue = marketValue.add(mv);
      }
      row.put("marketValue", mv);
      if (pos.getShares() > 0) {
        invested = invested.add(pos.getNetInvested());
      }

      EtfSignalEngine.TrendAdvice advice =
          engine.trendAdvice(
              ma.latestClose(),
              ma.ma5(),
              ma.ma20(),
              ma.ma20Slope(),
              ma.rise20Pct(),
              cfg.getBigRiseThresholdPct());
      row.put("trend", advice.trend());
      row.put("buyTier", advice.tier() == null ? null : advice.tier().name());
      row.put("buyTierReason", advice.reason());
      rows.add(row);
    }

    BigDecimal cash = cfg.getTotalCapital().subtract(invested).setScale(2, RoundingMode.HALF_UP);
    BigDecimal totalAsset = marketValue.add(cash);
    BigDecimal peak = cfg.getNavPeak() == null ? totalAsset : cfg.getNavPeak().max(totalAsset);

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("totalCapital", cfg.getTotalCapital());
    summary.put("marketValue", marketValue.setScale(2, RoundingMode.HALF_UP));
    summary.put("cash", cash);
    summary.put("totalAsset", totalAsset.setScale(2, RoundingMode.HALF_UP));
    summary.put("invested", invested.setScale(2, RoundingMode.HALF_UP));
    summary.put(
        "positionPct",
        cfg.getTotalCapital().compareTo(BigDecimal.ZERO) > 0
            ? invested
                .divide(cfg.getTotalCapital(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP)
            : null);
    summary.put("navPeak", peak);
    summary.put("drawdownPct", EtfSignalEngine.drawdownPct(totalAsset, peak));
    summary.put("calmUntil", cfg.getCalmUntil());
    summary.put("inCalm", modelService.inCalmPeriod());

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("config", cfg);
    resp.put("summary", summary);
    resp.put("positions", rows);
    return resp;
  }

  /* ─────────── ETF 池管理 ─────────── */

  @GetMapping("/pool")
  public List<EtfPool> pool() {
    return poolRepo.findByStatusOrderByIdAsc(EtfPool.STATUS_ACTIVE);
  }

  @PostMapping("/pool")
  @Transactional
  public EtfPool addPool(@RequestBody EtfPoolRequest req) {
    if (req.getStockCode() == null || req.getStockCode().isBlank()) {
      throw new IllegalArgumentException("stockCode 必填");
    }
    String code = normalizeCode(req.getStockCode());
    if (poolRepo.findByStockCode(code).isPresent()) {
      throw new IllegalArgumentException("已在池内: " + code);
    }
    if (poolRepo.countByStatus(EtfPool.STATUS_ACTIVE) >= EtfModelService.MAX_POOL_SIZE) {
      throw new IllegalArgumentException("池子已满 " + EtfModelService.MAX_POOL_SIZE + " 支（模型规则：池子 10 支）");
    }
    EtfPool pool = new EtfPool();
    pool.setStockCode(code);
    pool.setStockName(req.getStockName());
    pool.setCategory(
        EtfPool.CATEGORY_BROAD.equals(req.getCategory())
            ? EtfPool.CATEGORY_BROAD
            : EtfPool.CATEGORY_SECTOR);
    pool.setMemo(req.getMemo());
    EtfPool saved = poolRepo.save(pool);
    try {
      klineService.syncDaily(List.of(code), props.getKlineDaysBack());
    } catch (Exception e) {
      log.warn("新增 ETF 日K同步失败: {}", e.getMessage());
    }
    return saved;
  }

  @PatchMapping("/pool/{id}")
  @Transactional
  public EtfPool updatePool(@PathVariable Long id, @RequestBody EtfPoolRequest req) {
    EtfPool pool =
        poolRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("ETF 不存在: " + id));
    if (req.getStockCode() != null && !req.getStockCode().isBlank()) {
      String code = normalizeCode(req.getStockCode());
      poolRepo
          .findByStockCode(code)
          .filter(other -> !other.getId().equals(id))
          .ifPresent(
              other -> {
                throw new IllegalArgumentException("代码已被占用: " + code);
              });
      pool.setStockCode(code);
    }
    if (req.getStockName() != null) {
      pool.setStockName(req.getStockName());
    }
    if (req.getCategory() != null) {
      pool.setCategory(
          EtfPool.CATEGORY_BROAD.equals(req.getCategory())
              ? EtfPool.CATEGORY_BROAD
              : EtfPool.CATEGORY_SECTOR);
    }
    if (req.getMemo() != null) {
      pool.setMemo(req.getMemo());
    }
    return poolRepo.save(pool);
  }

  @DeleteMapping("/pool/{id}")
  @Transactional
  public Map<String, Object> removePool(@PathVariable Long id) {
    EtfPool pool =
        poolRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("ETF 不存在: " + id));
    if (!tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(id).isEmpty()) {
      // 有交易记录（复盘需要保留）→ 软移除
      pool.setStatus(EtfPool.STATUS_REMOVED);
      poolRepo.save(pool);
    } else {
      poolRepo.delete(pool);
    }
    return Map.of("ok", true);
  }

  /* ─────────── 交易记录 ─────────── */

  @GetMapping("/trades")
  public List<EtfTrade> trades(@RequestParam(required = false) Long poolId) {
    return poolId == null
        ? tradeRepo.findAllByOrderByTradeTimeDescIdDesc()
        : tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(poolId);
  }

  @PostMapping("/trades")
  public EtfTradeResult addTrade(@RequestBody EtfTradeRequest req) {
    return modelService.recordTrade(req);
  }

  @DeleteMapping("/trades/{id}")
  public Map<String, Object> deleteTrade(@PathVariable Long id) {
    modelService.deleteTrade(id);
    return Map.of("ok", true);
  }

  /* ─────────── 参数 ─────────── */

  @GetMapping("/config")
  public EtfModelConfig getConfig() {
    return modelService.config();
  }

  @PutMapping("/config")
  @Transactional
  public EtfModelConfig updateConfig(@RequestBody EtfConfigRequest req) {
    EtfModelConfig cfg = modelService.config();
    if (req.getTotalCapital() != null) cfg.setTotalCapital(req.getTotalCapital());
    if (req.getSingleMaxPct() != null) cfg.setSingleMaxPct(req.getSingleMaxPct());
    if (req.getPortfolioMaxPct() != null) cfg.setPortfolioMaxPct(req.getPortfolioMaxPct());
    if (req.getLightBatchMaxAmount() != null) cfg.setLightBatchMaxAmount(req.getLightBatchMaxAmount());
    if (req.getMidBatchMinAmount() != null) cfg.setMidBatchMinAmount(req.getMidBatchMinAmount());
    if (req.getMidBatchMaxAmount() != null) cfg.setMidBatchMaxAmount(req.getMidBatchMaxAmount());
    if (req.getBigRiseThresholdPct() != null) cfg.setBigRiseThresholdPct(req.getBigRiseThresholdPct());
    if (req.getPortfolioDrawdownPct() != null) cfg.setPortfolioDrawdownPct(req.getPortfolioDrawdownPct());
    if (req.getCalmDays() != null) cfg.setCalmDays(req.getCalmDays());
    if (req.getInceptionDate() != null) cfg.setInceptionDate(req.getInceptionDate());
    if (Boolean.TRUE.equals(req.getClearCalm())) cfg.setCalmUntil(null);
    return configRepo.save(cfg);
  }

  /* ─────────── 提醒历史 / 净值曲线 ─────────── */

  @GetMapping("/alerts")
  public Object alerts() {
    return alertRepo.findTop50ByOrderByTriggerAtDesc();
  }

  @GetMapping("/nav")
  public Object nav(@RequestParam(required = false) String from) {
    LocalDate fromDate =
        from != null && !from.isBlank()
            ? LocalDate.parse(from)
            : modelService.config().getInceptionDate();
    return navRepo.findBySnapDateGreaterThanEqualOrderBySnapDateAsc(fromDate);
  }

  /* ─────────── 手动触发 ─────────── */

  @PostMapping("/run")
  public Map<String, Object> run() {
    int triggered = monitorJob.scanThresholds();
    Map<String, Object> resp = new HashMap<>();
    resp.put("ok", true);
    resp.put("triggered", triggered);
    return resp;
  }

  @PostMapping("/sync-kline")
  public Map<String, Object> syncKline() {
    List<String> codes =
        poolRepo.findByStatusOrderByIdAsc(EtfPool.STATUS_ACTIVE).stream()
            .map(EtfPool::getStockCode)
            .toList();
    klineService.syncDaily(codes, props.getKlineDaysBack());
    return Map.of("ok", true, "codes", codes);
  }

  /* ─────────── helpers ─────────── */

  private static String normalizeCode(String raw) {
    String code = raw.trim().toUpperCase();
    if (!code.contains(".")) {
      // ETF 无后缀时按交易所惯例推断：5 开头沪市，1 开头深市
      code = code + (code.startsWith("5") ? ".SH" : ".SZ");
    }
    return code;
  }

  private static BigDecimal dailyChangePct(AStockDataQuoteService.QuoteSnapshot q) {
    if (q == null
        || q.latestPrice() == null
        || q.prevClosePrice() == null
        || q.prevClosePrice().compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return q.latestPrice()
        .subtract(q.prevClosePrice())
        .divide(q.prevClosePrice(), 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP);
  }
}
