package com.quant.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.config.NotificationProperties;
import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.PositionFillRequest;
import com.quant.dto.techai.PositionFillDTO;
import com.quant.dto.techai.StrategyLevelDTO;
import com.quant.dto.techai.TechAiAlertDTO;
import com.quant.dto.techai.TechAiPoolItemDTO;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.PotentialPool;
import com.quant.entity.PotentialPositionFill;
import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.PotentialPoolRepository;
import com.quant.repository.PotentialPositionFillRepository;
import com.quant.repository.TechAiQuoteSnapshotRepository;
import com.quant.service.monitor.MonitorService;
import com.quant.service.potential.PotentialAlertEngine;
import com.quant.service.potential.PotentialPoolSupport;
import com.quant.service.potential.PotentialPositionCalculator;
import com.quant.service.potential.PotentialQuoteAggregator;
import com.quant.service.techai.TechAiPositionEngine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 潜力监控 · 门面（God-class 拆分后的薄壳）。
 *
 * <p>原 PotentialService 1105 行已拆分为 service/potential/ 下的四个 Spring 组件：
 *
 * <ul>
 *   <li>{@link PotentialQuoteAggregator}：多源行情聚合 + 股票基础信息查找
 *   <li>{@link
 *       PotentialPositionCalculator}：持仓聚合计算（getOrCreatePosition/recomputeAggregates/effectiveTargetPrice/...）
 *   <li>{@link PotentialAlertEngine}：盘中扫盘与收盘确认 + 告警/持仓信号推送
 *   <li>{@link PotentialPoolSupport}：DTO 装配 + 格式化/解析无状态工具
 * </ul>
 *
 * <p>本类保留全部对外公开方法（HTTP/Controller 入口与 {@code @Scheduled} 定时作业），仅做编排：
 *
 * <ul>
 *   <li>HTTP: {@link #listPool}, {@link #addToPool}, {@link #updateField}, {@link #removeFromPool},
 *       {@link #recordFill}, {@link #listFills}, {@link #deleteFill}, {@link #listAlerts}
 *   <li>调度: {@link #monitorQuotes}（盘中）, {@link #confirmPositionSignals}（收盘）
 * </ul>
 *
 * <p>{@code @Transactional} 保留在 facade 公开方法上，DB 持久化集中在 facade， 行情/持仓计算委托给 helpers。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PotentialService {

  private final PotentialPoolRepository poolRepository;
  private final PotentialPositionFillRepository fillRepository;
  private final InvestPositionCommonRepository positionRepository;
  private final TechAiQuoteSnapshotRepository quoteRepository;
  private final InvestAlertRepository alertRepository;
  private final NotificationProperties notificationProperties;
  private final MonitorService monitorService;
  private final AStockDataQuoteService aStockDataQuoteService;
  private final TechAiPositionEngine positionEngine;
  private final PotentialQuoteAggregator quoteAggregator;
  private final PotentialPositionCalculator positionCalculator;
  private final PotentialAlertEngine alertEngine;

  // ===== Pool CRUD =====

  @Transactional(readOnly = true)
  public List<TechAiPoolItemDTO> listPool() {
    List<PotentialPool> pool = poolRepository.findAllByOrderByCreatedAtDesc();
    if (pool.isEmpty()) {
      return List.of();
    }
    List<String> codes = pool.stream().map(PotentialPool::getStockCode).toList();
    Map<String, TechAiQuoteSnapshot> quotes = quoteAggregator.latestQuotes(codes);
    Map<String, TradeStockBasic> basics = quoteAggregator.basics(codes);
    Map<String, InvestPositionCommon> posMap =
        positionRepository.findByStockCodeIn(codes).stream()
            .collect(Collectors.toMap(InvestPositionCommon::getStockCode, p -> p, (a, b) -> a));
    return pool.stream()
        .map(
            item ->
                toPoolDTO(
                    item,
                    posMap.get(item.getStockCode()),
                    quoteAggregator.basicFromMap(basics, item.getStockCode()),
                    quotes.get(item.getStockCode())))
        .toList();
  }

  @Transactional
  public TechAiPoolItemDTO addToPool(PoolSaveRequest request) {
    String keyword = request.getKeyword() == null ? "" : request.getKeyword().trim();
    if (keyword.isBlank()) {
      throw new IllegalArgumentException("股票代码不能为空");
    }
    String stockCode = quoteAggregator.resolveStockCode(keyword);
    Optional<PotentialPool> existing = poolRepository.findByStockCode(stockCode);
    if (existing.isPresent()) {
      throw new IllegalArgumentException("该股票已在监控池：" + stockCode);
    }

    PotentialPool pool = new PotentialPool();
    pool.setStockCode(stockCode);
    TradeStockBasic basic = quoteAggregator.basic(stockCode);
    if (basic != null) {
      pool.setStockName(basic.getStockName());
    }
    pool.setMemo(request.getMemo());
    PotentialPool saved = poolRepository.save(pool);

    InvestPositionCommon pos =
        PotentialPositionCalculator.newDefaultPosition(stockCode, request.getStatus());
    positionRepository.save(pos);

    return toPoolDTO(saved, pos, basic, latestQuote(saved.getStockCode()));
  }

  @Transactional
  public TechAiPoolItemDTO updateField(Integer id, PoolFieldUpdateRequest request) {
    PotentialPool pool =
        poolRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + id));
    InvestPositionCommon position =
        positionRepository
            .findByStockCodeAndPoolType(
                pool.getStockCode(), PotentialPositionCalculator.POOL_TYPE_POTENTIAL)
            .orElseThrow(() -> new IllegalStateException("持仓记录不存在：" + pool.getStockCode()));
    String field = request.getField() == null ? "" : request.getField().trim();
    String value = request.getValue();
    boolean blank = value == null || value.isBlank();
    switch (field) {
      case "status" -> position.setStatus(blank ? "watching" : value.trim());
      case "memo" -> pool.setMemo(blank ? null : value);
      case "alertMinute1mPct" ->
          position.setAlertMinute1mPct(PotentialPoolSupport.parsePositiveDecimal(value, field));
      case "alertMinute5mPct" ->
          position.setAlertMinute5mPct(PotentialPoolSupport.parsePositiveDecimal(value, field));
      case "alertDailyPct" ->
          position.setAlertDailyPct(PotentialPoolSupport.parsePositiveDecimal(value, field));
      case "alertThreeDayPct" ->
          position.setAlertThreeDayPct(PotentialPoolSupport.parsePositiveDecimal(value, field));
      case "alertTurnoverRatioPct" ->
          position.setAlertTurnoverRatioPct(
              PotentialPoolSupport.parsePositiveDecimal(value, field));
      case "addStepPct" ->
          position.setAddStepPct(PotentialPoolSupport.parsePositiveDecimal(value, field));
      case "trailPct" ->
          position.setTrailPct(PotentialPoolSupport.parsePositiveDecimal(value, field));
      case "addSizeSchedule" -> position.setAddSizeSchedule(blank ? "1,1,1" : value.trim());
      case "maxLots" ->
          position.setMaxLots(PotentialPoolSupport.parsePositiveDecimal(value, field));
      case "takeProfitPct" -> {
        position.setTakeProfitPct(PotentialPoolSupport.parsePositiveDecimal(value, field));
        if (position.getTargetSellPrice() == null) {
          position.setTargetSellPrice(
              positionCalculator.defaultTargetPrice(
                  position.getEntryPrice(), position.getTakeProfitPct()));
        }
      }
      case "breakevenAfterTp" ->
          position.setBreakevenAfterTp(PotentialPoolSupport.parseFlag(value));
      case "timeStopDays" ->
          position.setTimeStopDays(PotentialPoolSupport.parsePositiveInteger(value, field));
      case "useAtr" -> position.setUseAtr(PotentialPoolSupport.parseFlag(value));
      case "atrPeriod" ->
          position.setAtrPeriod(PotentialPoolSupport.parsePositiveInteger(value, field));
      case "atrAddMult" ->
          position.setAtrAddMult(PotentialPoolSupport.parsePositiveDecimal(value, field));
      case "atrTrailMult" ->
          position.setAtrTrailMult(PotentialPoolSupport.parsePositiveDecimal(value, field));
      case "targetSellPrice" ->
          position.setTargetSellPrice(PotentialPoolSupport.parsePositiveDecimal(value, field));
      default -> throw new IllegalArgumentException("不支持的字段：" + field);
    }
    positionRepository.save(position);
    poolRepository.save(pool);
    return toPoolDTO(
        pool,
        position,
        quoteAggregator.basic(pool.getStockCode()),
        latestQuote(pool.getStockCode()));
  }

  @Transactional
  public void removeFromPool(Integer id) {
    PotentialPool pool =
        poolRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + id));
    positionRepository
        .findByStockCodeAndPoolType(
            pool.getStockCode(), PotentialPositionCalculator.POOL_TYPE_POTENTIAL)
        .ifPresent(positionRepository::delete);
    fillRepository.deleteByPoolId(pool.getId());
    poolRepository.delete(pool);
  }

  // ===== Position & Fills =====

  @Transactional
  public TechAiPoolItemDTO recordFill(Integer poolId, PositionFillRequest request) {
    PotentialPool pool =
        poolRepository
            .findById(poolId)
            .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + poolId));
    InvestPositionCommon position = positionCalculator.getOrCreatePosition(pool.getStockCode());
    String action = request.getAction() == null ? "" : request.getAction().trim().toLowerCase();
    if (!List.of("open", "add", "reduce", "clear").contains(action)) {
      throw new IllegalArgumentException("不支持的操作：" + action);
    }
    if (request.getPrice() == null || request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("成交价必须大于 0");
    }
    BigDecimal currentLots =
        position.getPositionLots() == null ? BigDecimal.ZERO : position.getPositionLots();
    BigDecimal lots;
    if ("clear".equals(action)) {
      lots = currentLots;
      if (lots.compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("当前无持仓，无法清仓");
      }
    } else {
      lots = request.getLots();
      if (lots == null || lots.compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("成交手数必须大于 0");
      }
      if ("reduce".equals(action) && lots.compareTo(currentLots) > 0) {
        throw new IllegalArgumentException("减仓手数不能超过当前持仓");
      }
    }

    PotentialPositionFill fill = new PotentialPositionFill();
    fill.setPoolId(pool.getId());
    fill.setStockCode(pool.getStockCode());
    fill.setAction(action);
    fill.setPrice(request.getPrice());
    fill.setLots(lots);
    fill.setAmount(
        request
            .getPrice()
            .multiply(lots)
            .multiply(BigDecimal.valueOf(TechAiPositionEngine.SHARES_PER_LOT))
            .setScale(2, RoundingMode.HALF_UP));
    fill.setFee(request.getFee());
    fill.setNote(request.getNote());
    fill.setFilledAt(request.getFilledAt() == null ? LocalDateTime.now() : request.getFilledAt());
    fillRepository.save(fill);

    positionCalculator.recomputeAggregates(position);
    positionRepository.save(position);
    return toPoolDTO(
        pool,
        position,
        quoteAggregator.basic(pool.getStockCode()),
        latestQuote(pool.getStockCode()));
  }

  @Transactional(readOnly = true)
  public List<PositionFillDTO> listFills(Integer poolId) {
    poolRepository
        .findById(poolId)
        .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + poolId));
    return fillRepository.findByPoolIdOrderByFilledAtDescIdDesc(poolId).stream()
        .map(PotentialPoolSupport::toFillDTO)
        .toList();
  }

  @Transactional
  public TechAiPoolItemDTO deleteFill(Integer poolId, Long fillId) {
    PotentialPool pool =
        poolRepository
            .findById(poolId)
            .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + poolId));
    PotentialPositionFill fill =
        fillRepository
            .findById(fillId)
            .orElseThrow(() -> new IllegalArgumentException("成交记录不存在：" + fillId));
    if (!fill.getPoolId().equals(poolId)) {
      throw new IllegalArgumentException("成交记录与标的不匹配");
    }
    fillRepository.delete(fill);
    InvestPositionCommon position = positionCalculator.getOrCreatePosition(pool.getStockCode());
    positionCalculator.recomputeAggregates(position);
    positionRepository.save(position);
    return toPoolDTO(
        pool,
        position,
        quoteAggregator.basic(pool.getStockCode()),
        latestQuote(pool.getStockCode()));
  }

  // ===== Alerts =====

  @Transactional(readOnly = true)
  public List<TechAiAlertDTO> listAlerts() {
    List<String> codes =
        poolRepository.findAllByOrderByCreatedAtDesc().stream()
            .map(PotentialPool::getStockCode)
            .toList();
    if (codes.isEmpty()) {
      return List.of();
    }
    return alertRepository.findTop100ByStockCodeInOrderByTriggerAtDesc(codes).stream()
        .map(PotentialPoolSupport::toAlertDTO)
        .toList();
  }

  // ===== Scheduled =====

  @Scheduled(cron = "${notification.quote-monitor.cron:0 */1 9-15 * * MON-FRI}")
  @Transactional
  public int monitorQuotes() {
    NotificationProperties.QuoteMonitor cfg = notificationProperties.getQuoteMonitor();
    if (!cfg.isEnabled()) {
      return 0;
    }
    if (cfg.isRequireTradingTime() && !PotentialPoolSupport.isTradingTime()) {
      return 0;
    }
    List<PotentialPool> pool = poolRepository.findByStatusNotOrderByCreatedAtDesc("exited");
    if (pool.isEmpty()) {
      return 0;
    }
    List<String> codes = pool.stream().map(PotentialPool::getStockCode).toList();
    Map<String, TechAiQuoteSnapshot> quotes = quoteAggregator.latestQuotes(codes);
    Map<String, TradeStockBasic> basics = quoteAggregator.basics(codes);
    int triggered = alertEngine.monitorQuotes(cfg, pool, quotes, basics);
    if (triggered > 0) {
      log.info("潜力监控行情监控触发 {} 条告警", triggered);
    }
    // 2026-06-30 Monitor Fusion: 追加评估固定价/ATR/止盈止损 (与既有 % 提醒并存，signal type 不同)
    try {
      triggered += monitorService.scan(PotentialPositionCalculator.POOL_TYPE_POTENTIAL);
    } catch (Exception e) {
      log.warn("MonitorService.scan 异常（忽略）: {}", e.getMessage());
    }
    return triggered;
  }

  /**
   * 收盘确认：用 a-stock-data 实时收盘价判定持仓信号并推送（两段式中的确认段）。 实时价/收盘价统一走 a-stock-data 实时接口，trade_stock_daily
   * 同步延迟且不准确。
   */
  @Scheduled(cron = "${notification.position-confirm.cron:0 5 15 * * MON-FRI}")
  @Transactional
  public int confirmPositionSignals() {
    NotificationProperties.QuoteMonitor cfg = notificationProperties.getQuoteMonitor();
    if (!cfg.isEnabled()) {
      return 0;
    }
    List<PotentialPool> pool = poolRepository.findByStatusNotOrderByCreatedAtDesc("exited");
    if (pool.isEmpty()) {
      return 0;
    }
    Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap =
        aStockDataQuoteService.fetchQuotes(pool.stream().map(PotentialPool::getStockCode).toList());
    int triggered = alertEngine.confirmPositionSignals(cfg, pool, quoteMap);
    if (triggered > 0) {
      log.info("潜力监控收盘确认触发 {} 条持仓信号", triggered);
    }
    return triggered;
  }

  // ===== internal helpers =====

  /** 单标的最新行情快照（用于 addToPool/updateField/recordFill/deleteFill 后的回包）。 */
  private TechAiQuoteSnapshot latestQuote(String stockCode) {
    return quoteRepository.findFirstByStockCodeOrderByQuoteTimeDesc(stockCode).orElse(null);
  }

  /** DTO 装配：从 pool/pos/basic/quote 计算 positionEngine 视图、ATR、roadmap → toPoolDTO。 */
  private TechAiPoolItemDTO toPoolDTO(
      PotentialPool item,
      InvestPositionCommon pos,
      TradeStockBasic basic,
      TechAiQuoteSnapshot quote) {
    BigDecimal price = quote == null ? null : quote.getLatestPrice();
    BigDecimal atr =
        pos != null && positionCalculator.isAtrMode(pos)
            ? positionCalculator.atrFor(pos, item.getStockCode())
            : null;
    BigDecimal targetSellPrice = positionCalculator.effectiveTargetPrice(pos);
    TechAiPositionEngine.PoolView view =
        pos != null
            ? TechAiPositionEngine.from(pos).withTargetSellPrice(targetSellPrice)
            : TechAiPositionEngine.PoolView.builder().build();
    TechAiPositionEngine.PositionPlan plan = positionEngine.evaluate(view, price, atr);

    // 策略路线图：watching 时按现价预演全部档位
    List<StrategyLevelDTO> roadmap = List.of();
    boolean hasPosition =
        pos != null
            && pos.getPositionLots() != null
            && pos.getPositionLots().compareTo(BigDecimal.ZERO) > 0;
    if (!hasPosition && price != null && price.compareTo(BigDecimal.ZERO) > 0) {
      roadmap = positionEngine.computeRoadmap(price, view, atr);
    }

    return PotentialPoolSupport.toPoolDTO(
        item, pos, basic, quote, view, plan, roadmap, atr, targetSellPrice);
  }
}
