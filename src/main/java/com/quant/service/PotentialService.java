package com.quant.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import com.quant.dto.techai.TechAiAlertDTO;
import com.quant.dto.techai.TechAiPoolItemDTO;
import com.quant.entity.InvestAlert;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.PotentialPool;
import com.quant.entity.PotentialPositionFill;
import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.entity.TradeStockRealtimeKline;
import com.quant.entity.TradeStockRealtimeQuote;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.PotentialPoolRepository;
import com.quant.repository.PotentialPositionFillRepository;
import com.quant.repository.TechAiQuoteSnapshotRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.repository.TradeStockRealtimeKlineRepository;
import com.quant.repository.TradeStockRealtimeQuoteRepository;
import com.quant.service.techai.TechAiAlertCandidate;
import com.quant.service.techai.TechAiAlertRuleEngine;
import com.quant.service.techai.TechAiAlertThresholds;
import com.quant.service.techai.TechAiAtrCalculator;
import com.quant.service.techai.TechAiMarketContext;
import com.quant.service.techai.TechAiPositionEngine;
import com.quant.service.techai.TechAiStockCodeUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PotentialService {

  private static final String POOL_TYPE_POTENTIAL = "potential";

  private final PotentialPoolRepository poolRepository;
  private final InvestPositionCommonRepository positionRepository;
  private final PotentialPositionFillRepository fillRepository;
  private final TradeStockBasicRepository basicRepository;
  private final TradeStockDailyRepository dailyRepository;
  private final TechAiQuoteSnapshotRepository quoteRepository;
  private final TradeStockRealtimeQuoteRepository realtimeQuoteRepository;
  private final TradeStockRealtimeKlineRepository realtimeKlineRepository;
  private final InvestAlertRepository alertRepository;
  private final TechAiAlertRuleEngine ruleEngine;
  private final TechAiPositionEngine positionEngine;
  private final TechAiAtrCalculator atrCalculator;
  private final AStockDataQuoteService aStockDataQuoteService;
  private final SinaRealtimeQuoteService sinaRealtimeQuoteService;
  private final EastMoneyRealtimeQuoteService eastMoneyRealtimeQuoteService;
  private final BaostockMinuteQuoteService baostockMinuteQuoteService;
  private final NotificationService notificationService;
  private final NotificationProperties notificationProperties;

  /** 2026-06-30 Monitor Fusion — 用于在每次 schedule 中追加评估固定价/ATR/止盈止损 */
  private final com.quant.service.monitor.MonitorService monitorService;

  public PotentialService(
      PotentialPoolRepository poolRepository,
      PotentialPositionFillRepository fillRepository,
      InvestPositionCommonRepository positionRepository,
      TradeStockBasicRepository basicRepository,
      TradeStockDailyRepository dailyRepository,
      TechAiQuoteSnapshotRepository quoteRepository,
      TradeStockRealtimeQuoteRepository realtimeQuoteRepository,
      TradeStockRealtimeKlineRepository realtimeKlineRepository,
      InvestAlertRepository alertRepository,
      TechAiAlertRuleEngine ruleEngine,
      TechAiPositionEngine positionEngine,
      TechAiAtrCalculator atrCalculator,
      AStockDataQuoteService aStockDataQuoteService,
      SinaRealtimeQuoteService sinaRealtimeQuoteService,
      EastMoneyRealtimeQuoteService eastMoneyRealtimeQuoteService,
      BaostockMinuteQuoteService baostockMinuteQuoteService,
      NotificationService notificationService,
      NotificationProperties notificationProperties,
      com.quant.service.monitor.MonitorService monitorService) {
    this.poolRepository = poolRepository;
    this.fillRepository = fillRepository;
    this.positionRepository = positionRepository;
    this.basicRepository = basicRepository;
    this.dailyRepository = dailyRepository;
    this.quoteRepository = quoteRepository;
    this.realtimeQuoteRepository = realtimeQuoteRepository;
    this.realtimeKlineRepository = realtimeKlineRepository;
    this.alertRepository = alertRepository;
    this.ruleEngine = ruleEngine;
    this.positionEngine = positionEngine;
    this.atrCalculator = atrCalculator;
    this.aStockDataQuoteService = aStockDataQuoteService;
    this.sinaRealtimeQuoteService = sinaRealtimeQuoteService;
    this.eastMoneyRealtimeQuoteService = eastMoneyRealtimeQuoteService;
    this.baostockMinuteQuoteService = baostockMinuteQuoteService;
    this.notificationService = notificationService;
    this.notificationProperties = notificationProperties;
    this.monitorService = monitorService;
  }

  @Transactional(readOnly = true)
  public List<TechAiPoolItemDTO> listPool() {
    List<PotentialPool> pool = poolRepository.findAllByOrderByCreatedAtDesc();
    if (pool.isEmpty()) {
      return List.of();
    }
    List<String> codes = pool.stream().map(PotentialPool::getStockCode).toList();
    Map<String, TechAiQuoteSnapshot> quotes = latestQuotes(codes);
    Map<String, TradeStockBasic> basics = basics(codes);
    Map<String, InvestPositionCommon> posMap =
        positionRepository.findByStockCodeIn(codes).stream()
            .collect(Collectors.toMap(InvestPositionCommon::getStockCode, p -> p, (a, b) -> a));
    return pool.stream()
        .map(
            item ->
                toPoolDTO(
                    item,
                    posMap.get(item.getStockCode()),
                    basicFromMap(basics, item.getStockCode()),
                    quotes.get(item.getStockCode())))
        .toList();
  }

  @Transactional
  public TechAiPoolItemDTO addToPool(PoolSaveRequest request) {
    String keyword = request.getKeyword() == null ? "" : request.getKeyword().trim();
    if (keyword.isBlank()) {
      throw new IllegalArgumentException("股票代码不能为空");
    }
    String stockCode = resolveStockCode(keyword);
    Optional<PotentialPool> existing = poolRepository.findByStockCode(stockCode);
    if (existing.isPresent()) {
      throw new IllegalArgumentException("该股票已在监控池：" + stockCode);
    }

    PotentialPool pool = new PotentialPool();
    pool.setStockCode(stockCode);
    TradeStockBasic basic = basic(stockCode);
    if (basic != null) {
      pool.setStockName(basic.getStockName());
    }
    pool.setMemo(request.getMemo());
    PotentialPool saved = poolRepository.save(pool);

    InvestPositionCommon pos = new InvestPositionCommon();
    pos.setStockCode(stockCode);
    pos.setPoolType(POOL_TYPE_POTENTIAL);
    pos.setStatus(
        request.getStatus() == null || request.getStatus().isBlank()
            ? "watching"
            : request.getStatus());
    pos.setPositionState("none");
    pos.setPositionLots(BigDecimal.ZERO);
    pos.setAddCount(0);
    pos.setRealizedPnl(BigDecimal.ZERO);
    pos.setTakeProfitDone(0);
    pos.setAddStepPct(BigDecimal.valueOf(10));
    pos.setTrailPct(BigDecimal.valueOf(10));
    pos.setAddSizeSchedule("1,1,1");
    pos.setTakeProfitPct(BigDecimal.valueOf(50));
    pos.setBreakevenAfterTp(1);
    pos.setUseAtr(0);
    pos.setAtrPeriod(14);
    pos.setAtrAddMult(BigDecimal.ONE);
    pos.setAtrTrailMult(BigDecimal.valueOf(2));
    pos.setAlertState("none");
    positionRepository.save(pos);

    return toPoolDTO(
        saved,
        pos,
        basic,
        quoteRepository
            .findFirstByStockCodeOrderByQuoteTimeDesc(saved.getStockCode())
            .orElse(null));
  }

  @Transactional
  public TechAiPoolItemDTO updateField(Integer id, PoolFieldUpdateRequest request) {
    PotentialPool pool =
        poolRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + id));
    InvestPositionCommon position =
        positionRepository
            .findByStockCodeAndPoolType(pool.getStockCode(), POOL_TYPE_POTENTIAL)
            .orElseThrow(() -> new IllegalStateException("持仓记录不存在：" + pool.getStockCode()));
    String field = request.getField() == null ? "" : request.getField().trim();
    String value = request.getValue();
    boolean blank = value == null || value.isBlank();
    switch (field) {
      case "status" -> position.setStatus(blank ? "watching" : value.trim());
      case "memo" -> pool.setMemo(blank ? null : value);
      case "alertMinute1mPct" -> position.setAlertMinute1mPct(parsePositiveDecimal(value, field));
      case "alertMinute5mPct" -> position.setAlertMinute5mPct(parsePositiveDecimal(value, field));
      case "alertDailyPct" -> position.setAlertDailyPct(parsePositiveDecimal(value, field));
      case "alertThreeDayPct" -> position.setAlertThreeDayPct(parsePositiveDecimal(value, field));
      case "alertTurnoverRatioPct" ->
          position.setAlertTurnoverRatioPct(parsePositiveDecimal(value, field));
      case "addStepPct" -> position.setAddStepPct(parsePositiveDecimal(value, field));
      case "trailPct" -> position.setTrailPct(parsePositiveDecimal(value, field));
      case "addSizeSchedule" -> position.setAddSizeSchedule(blank ? "1,1,1" : value.trim());
      case "maxLots" -> position.setMaxLots(parsePositiveDecimal(value, field));
      case "takeProfitPct" -> {
        position.setTakeProfitPct(parsePositiveDecimal(value, field));
        if (position.getTargetSellPrice() == null) {
          position.setTargetSellPrice(
              defaultTargetPrice(position.getEntryPrice(), position.getTakeProfitPct()));
        }
      }
      case "breakevenAfterTp" -> position.setBreakevenAfterTp(parseFlag(value));
      case "timeStopDays" -> position.setTimeStopDays(parsePositiveInteger(value, field));
      case "useAtr" -> position.setUseAtr(parseFlag(value));
      case "atrPeriod" -> position.setAtrPeriod(parsePositiveInteger(value, field));
      case "atrAddMult" -> position.setAtrAddMult(parsePositiveDecimal(value, field));
      case "atrTrailMult" -> position.setAtrTrailMult(parsePositiveDecimal(value, field));
      case "targetSellPrice" -> position.setTargetSellPrice(parsePositiveDecimal(value, field));
      default -> throw new IllegalArgumentException("不支持的字段：" + field);
    }
    positionRepository.save(position);
    poolRepository.save(pool);
    return toPoolDTO(
        pool,
        position,
        basic(pool.getStockCode()),
        quoteRepository.findFirstByStockCodeOrderByQuoteTimeDesc(pool.getStockCode()).orElse(null));
  }

  @Transactional
  public void removeFromPool(Integer id) {
    PotentialPool pool =
        poolRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + id));
    positionRepository
        .findByStockCodeAndPoolType(pool.getStockCode(), POOL_TYPE_POTENTIAL)
        .ifPresent(positionRepository::delete);
    fillRepository.deleteByPoolId(pool.getId());
    poolRepository.delete(pool);
  }

  @Transactional
  public TechAiPoolItemDTO recordFill(Integer poolId, PositionFillRequest request) {
    PotentialPool pool =
        poolRepository
            .findById(poolId)
            .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + poolId));
    InvestPositionCommon position = getOrCreatePosition(pool.getStockCode());
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

    recomputeAggregates(position);
    positionRepository.save(position);
    return toPoolDTO(
        pool,
        position,
        basic(pool.getStockCode()),
        quoteRepository.findFirstByStockCodeOrderByQuoteTimeDesc(pool.getStockCode()).orElse(null));
  }

  @Transactional(readOnly = true)
  public List<PositionFillDTO> listFills(Integer poolId) {
    poolRepository
        .findById(poolId)
        .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + poolId));
    return fillRepository.findByPoolIdOrderByFilledAtDescIdDesc(poolId).stream()
        .map(this::toFillDTO)
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
    InvestPositionCommon position = getOrCreatePosition(pool.getStockCode());
    recomputeAggregates(position);
    positionRepository.save(position);
    return toPoolDTO(
        pool,
        position,
        basic(pool.getStockCode()),
        quoteRepository.findFirstByStockCodeOrderByQuoteTimeDesc(pool.getStockCode()).orElse(null));
  }

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
        .map(this::toAlertDTO)
        .toList();
  }

  @Scheduled(cron = "${notification.quote-monitor.cron:0 */1 9-15 * * MON-FRI}")
  @Transactional
  public int monitorQuotes() {
    NotificationProperties.QuoteMonitor cfg = notificationProperties.getQuoteMonitor();
    if (!cfg.isEnabled()) {
      return 0;
    }
    if (cfg.isRequireTradingTime() && !isTradingTime()) {
      return 0;
    }
    List<PotentialPool> pool = poolRepository.findByStatusNotOrderByCreatedAtDesc("exited");
    if (pool.isEmpty()) {
      return 0;
    }
    List<String> codes = pool.stream().map(PotentialPool::getStockCode).toList();
    Map<String, TechAiQuoteSnapshot> quotes = latestQuotes(codes);
    Map<String, TradeStockBasic> basics = basics(codes);
    int triggered = 0;
    for (PotentialPool item : pool) {
      TechAiQuoteSnapshot quote = quotes.get(item.getStockCode());
      if (quote == null) {
        continue;
      }
      InvestPositionCommon position =
          positionRepository
              .findByStockCodeAndPoolType(item.getStockCode(), POOL_TYPE_POTENTIAL)
              .orElse(null);
      String stockName = displayStockName(item, basicFromMap(basics, item.getStockCode()));
      TechAiMarketContext ctx = buildContext(item.getStockCode(), stockName, quote);
      for (TechAiAlertCandidate candidate : ruleEngine.evaluate(ctx, thresholds(position))) {
        if (shouldPush(candidate, cfg)) {
          saveAndPush(candidate, quote);
          triggered++;
        }
      }
      triggered += evaluateIntradayPosition(item, position, quote, cfg);
    }
    if (triggered > 0) {
      log.info("潜力监控行情监控触发 {} 条告警", triggered);
    }
    // 2026-06-30 Monitor Fusion: 追加评估固定价/ATR/止盈止损 (与既有 % 提醒并存，signal type 不同)
    try {
      triggered += monitorService.scan(POOL_TYPE_POTENTIAL);
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
    int triggered = 0;
    for (PotentialPool item : pool) {
      InvestPositionCommon position =
          positionRepository
              .findByStockCodeAndPoolType(item.getStockCode(), POOL_TYPE_POTENTIAL)
              .orElse(null);
      if (position == null
          || position.getPositionLots() == null
          || position.getPositionLots().compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      AStockDataQuoteService.QuoteSnapshot snapshot =
          quoteMap.get(
              item.getStockCode() == null
                  ? ""
                  : item.getStockCode().trim().toUpperCase(Locale.ROOT));
      if (snapshot == null
          || snapshot.latestPrice() == null
          || snapshot.latestPrice().compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      BigDecimal close = snapshot.latestPrice();
      // 历史 K 线仍来自 trade_stock_daily（用于峰值参考与 ATR）
      List<TradeStockDaily> recentKline =
          dailyRepository.findTop6ByStockCodeOrderByTradeDateDesc(item.getStockCode());
      BigDecimal historicalHigh = recentKline.isEmpty() ? null : recentKline.get(0).getHighPrice();
      BigDecimal atr = isAtrMode(position) ? atrFor(position, item.getStockCode()) : null;
      BigDecimal peak = position.getPeakPrice() == null ? close : position.getPeakPrice();
      if (historicalHigh != null) {
        peak = peak.max(historicalHigh);
      }
      peak = peak.max(close);
      position.setPeakPrice(peak);
      TechAiPositionEngine.PositionPlan plan =
          positionEngine.evaluate(TechAiPositionEngine.from(position), close, atr);
      position.setStopPrice(plan.getStopPrice());
      positionRepository.save(position);
      if (plan.getPendingSignal() != null
          && pushPositionSignal(item, position, close, plan, true, cfg)) {
        triggered++;
      }
    }
    if (triggered > 0) {
      log.info("潜力监控收盘确认触发 {} 条持仓信号", triggered);
    }
    return triggered;
  }

  // ===== private helpers =====

  private int evaluateIntradayPosition(
      PotentialPool item,
      InvestPositionCommon position,
      TechAiQuoteSnapshot quote,
      NotificationProperties.QuoteMonitor cfg) {
    if (position == null
        || position.getPositionLots() == null
        || position.getPositionLots().compareTo(BigDecimal.ZERO) <= 0) {
      return 0;
    }
    BigDecimal price = quote.getLatestPrice();
    if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
      return 0;
    }
    BigDecimal atr = isAtrMode(position) ? atrFor(position, item.getStockCode()) : null;
    BigDecimal peak = position.getPeakPrice() == null ? price : position.getPeakPrice().max(price);
    position.setPeakPrice(peak);
    TechAiPositionEngine.PositionPlan plan =
        positionEngine.evaluate(TechAiPositionEngine.from(position), price, atr);
    position.setStopPrice(plan.getStopPrice());
    positionRepository.save(position);
    if (plan.getPendingSignal() == null) {
      return 0;
    }
    return pushPositionSignal(item, position, price, plan, false, cfg) ? 1 : 0;
  }

  private boolean pushPositionSignal(
      PotentialPool item,
      InvestPositionCommon position,
      BigDecimal price,
      TechAiPositionEngine.PositionPlan plan,
      boolean confirm,
      NotificationProperties.QuoteMonitor cfg) {
    String signal = plan.getPendingSignal();
    String signalType = "position_" + signal.toLowerCase() + (confirm ? "_confirm" : "_warn");
    if (!shouldPushPosition(item.getStockCode(), signalType, confirm, cfg)) {
      return false;
    }
    String stockName = displayStockName(item, basic(item.getStockCode()));
    String phase = confirm ? "收盘确认" : "盘中预警";
    String actionLabel =
        switch (signal) {
          case TechAiPositionEngine.SIGNAL_STOP -> "清仓信号";
          case TechAiPositionEngine.SIGNAL_ADD -> "加仓信号";
          case TechAiPositionEngine.SIGNAL_TP -> "止盈信号";
          default -> "持仓信号";
        };
    String title =
        String.format(
            "【%s·%s】%s(%s) @ %s", actionLabel, phase, stockName, item.getStockCode(), fmt(price));
    String content = buildPositionContent(item, position, stockName, price, plan, signal, phase);

    InvestAlert alert = new InvestAlert();
    alert.setStockCode(item.getStockCode());
    alert.setSignalType(signalType);
    alert.setLevel(positionLevel(signal));
    alert.setTitle(title);
    alert.setContent(content);
    alert.setTriggerPrice(price);
    alert.setTriggerAt(LocalDateTime.now());
    alert.setChannels("serverchan");
    boolean sent = notificationService.sendServerChan(title, content);
    alert.setPushed(sent ? 1 : 0);
    alert.setReadFlag(0);
    alertRepository.save(alert);
    return true;
  }

  private boolean shouldPushPosition(
      String stockCode,
      String signalType,
      boolean confirm,
      NotificationProperties.QuoteMonitor cfg) {
    LocalDateTime now = LocalDateTime.now();
    if (!confirm) {
      return alertRepository
          .findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(stockCode, signalType)
          .map(
              a ->
                  a.getTriggerAt() == null
                      || a.getTriggerAt().plusMinutes(cfg.getCooldownMinutes()).isBefore(now))
          .orElse(true);
    }
    LocalDate today = LocalDate.now();
    return !alertRepository.existsByStockCodeAndSignalTypeAndTriggerAtBetween(
        stockCode,
        signalType,
        today.atStartOfDay(),
        today.plusDays(1).atStartOfDay().minusNanos(1));
  }

  private int positionLevel(String signal) {
    return switch (signal) {
      case TechAiPositionEngine.SIGNAL_STOP -> 3;
      case TechAiPositionEngine.SIGNAL_ADD, TechAiPositionEngine.SIGNAL_TP -> 2;
      default -> 1;
    };
  }

  private String buildPositionContent(
      PotentialPool item,
      InvestPositionCommon position,
      String stockName,
      BigDecimal price,
      TechAiPositionEngine.PositionPlan plan,
      String signal,
      String phase) {
    String advice =
        switch (signal) {
          case TechAiPositionEngine.SIGNAL_STOP -> "现价已触及移动止损，建议清仓离场。";
          case TechAiPositionEngine.SIGNAL_ADD ->
              String.format(
                  "现价突破加仓位，建议加仓 %s 手。",
                  plan.getNextAddLots() == null ? "-" : fmt(plan.getNextAddLots()));
          case TechAiPositionEngine.SIGNAL_TP ->
              String.format(
                  "现价达到目标价，建议减仓 %s%% 止盈。",
                  position == null || position.getTakeProfitPct() == null
                      ? "50"
                      : fmt(position.getTakeProfitPct()));
          default -> "";
        };
    String warn = plan.isStopBelowCost() ? "\n\n> ⚠️ 当前止损价低于平均成本，触发止损将产生亏损。" : "";
    return String.format(
        """
                ## %s（%s）· %s

                **建议**：%s

                **现价**：%s
                **平均成本**：%s
                **持仓手数**：%s
                **移动止损**：%s
                **下一加仓价**：%s
                **目标止盈价**：%s
                **浮动盈亏**：%s（%s%%）%s
                """,
        stockName,
        item.getStockCode(),
        phase,
        advice,
        fmt(price),
        fmt(position != null ? position.getAvgCost() : null),
        fmt(position != null ? position.getPositionLots() : null),
        fmt(plan.getStopPrice()),
        fmt(plan.getNextAddPrice()),
        fmt(plan.getTargetPrice()),
        fmt(plan.getFloatingPnl()),
        fmt(plan.getFloatingPnlPct()),
        warn);
  }

  private InvestPositionCommon getOrCreatePosition(String stockCode) {
    return positionRepository
        .findByStockCodeAndPoolType(stockCode, POOL_TYPE_POTENTIAL)
        .orElseGet(
            () -> {
              InvestPositionCommon pos = new InvestPositionCommon();
              pos.setStockCode(stockCode);
              pos.setPoolType(POOL_TYPE_POTENTIAL);
              pos.setStatus("watching");
              pos.setAlertState("none");
              pos.setPositionState("none");
              pos.setPositionLots(BigDecimal.ZERO);
              pos.setRealizedPnl(BigDecimal.ZERO);
              pos.setAddCount(0);
              pos.setTakeProfitDone(0);
              pos.setAddStepPct(BigDecimal.valueOf(10));
              pos.setTrailPct(BigDecimal.valueOf(10));
              pos.setAddSizeSchedule("1,1,1");
              pos.setTakeProfitPct(BigDecimal.valueOf(50));
              pos.setBreakevenAfterTp(1);
              pos.setUseAtr(0);
              pos.setAtrPeriod(14);
              pos.setAtrAddMult(BigDecimal.ONE);
              pos.setAtrTrailMult(BigDecimal.valueOf(2));
              return positionRepository.save(pos);
            });
  }

  private String fmt(BigDecimal v) {
    return v == null ? "-" : v.stripTrailingZeros().toPlainString();
  }

  private void recomputeAggregates(InvestPositionCommon position) {
    PotentialPool pool =
        poolRepository
            .findByStockCode(position.getStockCode())
            .orElseThrow(
                () -> new IllegalStateException("pool not found for " + position.getStockCode()));
    List<PotentialPositionFill> fills =
        fillRepository.findByPoolIdOrderByFilledAtAscIdAsc(pool.getId());
    BigDecimal target = position.getTargetSellPrice();

    BigDecimal lots = BigDecimal.ZERO;
    BigDecimal avg = null;
    BigDecimal realized = BigDecimal.ZERO;
    int addCount = 0;
    BigDecimal lastBuyPrice = null;
    BigDecimal entry = null;
    BigDecimal peak = null;
    LocalDateTime openedAt = null;
    boolean tpDone = false;
    boolean scaled = false;

    for (PotentialPositionFill fill : fills) {
      String action = fill.getAction();
      BigDecimal price = fill.getPrice();
      BigDecimal fl = fill.getLots();
      if ("open".equals(action) || "add".equals(action)) {
        if (lots.compareTo(BigDecimal.ZERO) <= 0) {
          avg = price;
          lots = fl;
          entry = price;
          if (target == null) {
            target = defaultTargetPrice(entry, position.getTakeProfitPct());
          }
          addCount = 0;
          peak = price;
          openedAt = fill.getFilledAt();
          tpDone = false;
          scaled = false;
        } else {
          BigDecimal newLots = lots.add(fl);
          avg = avg.multiply(lots).add(price.multiply(fl)).divide(newLots, 4, RoundingMode.HALF_UP);
          lots = newLots;
          addCount++;
          peak = peak == null ? price : peak.max(price);
        }
        lastBuyPrice = price;
      } else {
        BigDecimal sellLots = "clear".equals(action) ? lots : fl.min(lots);
        if (avg != null && sellLots.compareTo(BigDecimal.ZERO) > 0) {
          realized =
              realized.add(
                  price
                      .subtract(avg)
                      .multiply(sellLots)
                      .multiply(BigDecimal.valueOf(TechAiPositionEngine.SHARES_PER_LOT)));
        }
        lots = lots.subtract(sellLots);
        if (target != null && price.compareTo(target) >= 0) {
          tpDone = true;
        }
        if (lots.compareTo(BigDecimal.ZERO) <= 0) {
          lots = BigDecimal.ZERO;
        } else {
          scaled = true;
        }
      }
    }

    boolean hasPosition = lots.compareTo(BigDecimal.ZERO) > 0;
    position.setPositionLots(lots);
    position.setAddCount(hasPosition ? addCount : 0);
    position.setRealizedPnl(realized.setScale(2, RoundingMode.HALF_UP));

    if (fills.isEmpty()) {
      position.setPositionState("none");
      position.setAvgCost(null);
      position.setEntryPrice(null);
      position.setLastAddPrice(null);
      position.setPeakPrice(null);
      position.setStopPrice(null);
      position.setTotalInvested(BigDecimal.ZERO);
      position.setOpenedAt(null);
      position.setTakeProfitDone(0);
      return;
    }

    if (!hasPosition) {
      position.setPositionState("exited");
      position.setAvgCost(null);
      position.setEntryPrice(null);
      position.setLastAddPrice(null);
      position.setPeakPrice(null);
      position.setStopPrice(null);
      position.setTotalInvested(BigDecimal.ZERO);
      position.setOpenedAt(openedAt);
      position.setTakeProfitDone(0);
      if (!"exited".equals(position.getStatus())) {
        position.setStatus("exited");
      }
      return;
    }

    position.setAvgCost(avg.setScale(2, RoundingMode.HALF_UP));
    position.setEntryPrice(entry);
    if (position.getTargetSellPrice() == null) {
      position.setTargetSellPrice(target);
    }
    position.setLastAddPrice(lastBuyPrice);
    BigDecimal effectivePeak = peak == null ? entry : peak;
    position.setPeakPrice(effectivePeak);
    position.setTotalInvested(
        avg.multiply(lots)
            .multiply(BigDecimal.valueOf(TechAiPositionEngine.SHARES_PER_LOT))
            .setScale(2, RoundingMode.HALF_UP));
    position.setOpenedAt(openedAt);
    position.setTakeProfitDone(tpDone ? 1 : 0);
    position.setPositionState(scaled ? "scaled" : "holding");
    if (!"holding".equals(position.getStatus())) {
      position.setStatus("holding");
    }

    BigDecimal atr = isAtrMode(position) ? atrFor(position, position.getStockCode()) : null;
    TechAiPositionEngine.PositionPlan plan =
        positionEngine.evaluate(TechAiPositionEngine.from(position), effectivePeak, atr);
    position.setStopPrice(plan.getStopPrice());
  }

  private PositionFillDTO toFillDTO(PotentialPositionFill fill) {
    return PositionFillDTO.builder()
        .id(fill.getId())
        .poolId(fill.getPoolId())
        .stockCode(fill.getStockCode())
        .action(fill.getAction())
        .price(fill.getPrice())
        .lots(fill.getLots())
        .amount(fill.getAmount())
        .fee(fill.getFee())
        .note(fill.getNote())
        .filledAt(fill.getFilledAt())
        .build();
  }

  private TechAiPoolItemDTO toPoolDTO(
      PotentialPool item,
      InvestPositionCommon pos,
      TradeStockBasic basic,
      TechAiQuoteSnapshot quote) {
    BigDecimal dailyChange =
        quote == null ? null : pctChange(quote.getLatestPrice(), quote.getPrevClosePrice());
    BigDecimal price = quote == null ? null : quote.getLatestPrice();
    BigDecimal atr = pos != null && isAtrMode(pos) ? atrFor(pos, item.getStockCode()) : null;
    BigDecimal targetSellPrice = effectiveTargetPrice(pos);
    TechAiPositionEngine.PoolView view =
        pos != null
            ? TechAiPositionEngine.from(pos).withTargetSellPrice(targetSellPrice)
            : TechAiPositionEngine.PoolView.builder().build();
    TechAiPositionEngine.PositionPlan plan = positionEngine.evaluate(view, price, atr);

    // 策略路线图：watching 时按现价预演全部档位
    List<com.quant.dto.techai.StrategyLevelDTO> roadmap = List.of();
    boolean hasPosition =
        pos != null
            && pos.getPositionLots() != null
            && pos.getPositionLots().compareTo(BigDecimal.ZERO) > 0;
    if (!hasPosition && price != null && price.compareTo(BigDecimal.ZERO) > 0) {
      roadmap = positionEngine.computeRoadmap(price, view, atr);
    }

    return TechAiPoolItemDTO.builder()
        .id(item.getId())
        .stockCode(item.getStockCode())
        .qmtCode(TechAiStockCodeUtils.toQmtCode(item.getStockCode()))
        .stockName(displayStockName(item, basic))
        .status(pos != null ? pos.getStatus() : "watching")
        .memo(item.getMemo())
        .latestPrice(price)
        .dailyChangePct(dailyChange)
        .turnoverRate(quote == null ? null : quote.getTurnoverRate())
        .volume(quote == null ? null : quote.getVolume())
        .quoteTime(quote == null ? null : quote.getQuoteTime())
        .alertMinute1mPct(pos != null ? pos.getAlertMinute1mPct() : null)
        .alertMinute5mPct(pos != null ? pos.getAlertMinute5mPct() : null)
        .alertDailyPct(pos != null ? pos.getAlertDailyPct() : null)
        .alertThreeDayPct(pos != null ? pos.getAlertThreeDayPct() : null)
        .alertTurnoverRatioPct(pos != null ? pos.getAlertTurnoverRatioPct() : null)
        .entryPrice(pos != null ? pos.getEntryPrice() : null)
        .positionLots(pos != null ? pos.getPositionLots() : null)
        .avgCost(pos != null ? pos.getAvgCost() : null)
        .totalInvested(pos != null ? pos.getTotalInvested() : null)
        .addCount(pos != null ? pos.getAddCount() : null)
        .lastAddPrice(pos != null ? pos.getLastAddPrice() : null)
        .peakPrice(pos != null ? pos.getPeakPrice() : null)
        .stopPrice(pos != null ? pos.getStopPrice() : null)
        .realizedPnl(pos != null ? pos.getRealizedPnl() : null)
        .positionState(pos != null ? pos.getPositionState() : null)
        .takeProfitDone(
            pos != null && pos.getTakeProfitDone() != null && pos.getTakeProfitDone() == 1)
        .openedAt(pos != null ? pos.getOpenedAt() : null)
        .addStepPct(pos != null ? pos.getAddStepPct() : null)
        .trailPct(pos != null ? pos.getTrailPct() : null)
        .addSizeSchedule(pos != null ? pos.getAddSizeSchedule() : null)
        .maxLots(pos != null ? pos.getMaxLots() : null)
        .takeProfitPct(pos != null ? pos.getTakeProfitPct() : null)
        .breakevenAfterTp(
            pos != null && pos.getBreakevenAfterTp() != null && pos.getBreakevenAfterTp() == 1)
        .timeStopDays(pos != null ? pos.getTimeStopDays() : null)
        .useAtr(pos != null && isAtrMode(pos))
        .atrPeriod(pos != null ? pos.getAtrPeriod() : null)
        .atrAddMult(pos != null ? pos.getAtrAddMult() : null)
        .atrTrailMult(pos != null ? pos.getAtrTrailMult() : null)
        .targetSellPrice(targetSellPrice)
        .nextAddPrice(plan.getNextAddPrice())
        .nextAddLots(plan.getNextAddLots())
        .currentStopPrice(plan.getStopPrice())
        .floatingPnl(plan.getFloatingPnl())
        .floatingPnlPct(plan.getFloatingPnlPct())
        .atrValue(atr)
        .stopBelowCost(plan.isStopBelowCost())
        .pendingSignal(plan.getPendingSignal())
        .roadmap(roadmap)
        .createdAt(item.getCreatedAt())
        .updatedAt(item.getUpdatedAt())
        .build();
  }

  private BigDecimal effectiveTargetPrice(InvestPositionCommon pos) {
    if (pos == null) {
      return null;
    }
    if (pos.getTargetSellPrice() != null) {
      return pos.getTargetSellPrice();
    }
    return defaultTargetPrice(pos.getEntryPrice(), pos.getTakeProfitPct());
  }

  private BigDecimal defaultTargetPrice(BigDecimal entryPrice, BigDecimal takeProfitPct) {
    if (entryPrice == null
        || takeProfitPct == null
        || entryPrice.compareTo(BigDecimal.ZERO) <= 0
        || takeProfitPct.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    BigDecimal multiplier =
        BigDecimal.ONE.add(takeProfitPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
    return entryPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
  }

  private boolean isAtrMode(InvestPositionCommon pos) {
    return pos != null && pos.getUseAtr() != null && pos.getUseAtr() == 1;
  }

  private BigDecimal atrFor(InvestPositionCommon pos, String stockCode) {
    int period = pos.getAtrPeriod() == null || pos.getAtrPeriod() <= 0 ? 14 : pos.getAtrPeriod();
    List<TradeStockDaily> recent =
        dailyRepository.findTop30ByStockCodeOrderByTradeDateDesc(stockCode);
    return atrCalculator.atr(recent, period);
  }

  private TechAiAlertThresholds thresholds(InvestPositionCommon pos) {
    return TechAiAlertThresholds.builder()
        .minute1Pct(pos != null ? pos.getAlertMinute1mPct() : null)
        .minute5Pct(pos != null ? pos.getAlertMinute5mPct() : null)
        .dailyPct(pos != null ? pos.getAlertDailyPct() : null)
        .threeDayPct(pos != null ? pos.getAlertThreeDayPct() : null)
        .turnoverRatioPct(pos != null ? pos.getAlertTurnoverRatioPct() : null)
        .build();
  }

  private TechAiMarketContext buildContext(
      String stockCode, String stockName, TechAiQuoteSnapshot quote) {
    List<TradeStockDaily> recent =
        dailyRepository.findTop6ByStockCodeOrderByTradeDateDesc(stockCode);
    BigDecimal avgTurnover5d = averageTurnover(recent.stream().limit(5).toList());
    BigDecimal close3d = recent.size() >= 3 ? recent.get(2).getClosePrice() : null;
    return TechAiMarketContext.builder()
        .stockCode(stockCode)
        .stockName(stockName)
        .quoteTime(quote.getQuoteTime())
        .latestPrice(quote.getLatestPrice())
        .prevClosePrice(quote.getPrevClosePrice())
        .openPrice(quote.getOpenPrice())
        .minute1OpenPrice(quote.getMinute1OpenPrice())
        .minute5OpenPrice(quote.getMinute5OpenPrice())
        .turnoverRate(quote.getTurnoverRate())
        .avgTurnoverRate5d(avgTurnover5d)
        .closePrice3TradingDaysAgo(close3d)
        .volume(quote.getVolume())
        .build();
  }

  private boolean shouldPush(
      TechAiAlertCandidate candidate, NotificationProperties.QuoteMonitor cfg) {
    String signalType =
        candidate.ruleType() + ":" + candidate.threshold().stripTrailingZeros().toPlainString();
    LocalDateTime now = LocalDateTime.now();
    if (candidate.minuteRule()) {
      return alertRepository
          .findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(candidate.stockCode(), signalType)
          .map(
              alert ->
                  alert.getTriggerAt() == null
                      || alert.getTriggerAt().plusMinutes(cfg.getCooldownMinutes()).isBefore(now))
          .orElse(true);
    }
    if (!cfg.isDailyDedupe()) {
      return true;
    }
    LocalDate today = LocalDate.now();
    return !alertRepository.existsByStockCodeAndSignalTypeAndTriggerAtBetween(
        candidate.stockCode(),
        signalType,
        today.atStartOfDay(),
        today.plusDays(1).atStartOfDay().minusNanos(1));
  }

  private void saveAndPush(TechAiAlertCandidate candidate, TechAiQuoteSnapshot quote) {
    InvestAlert alert = new InvestAlert();
    alert.setStockCode(candidate.stockCode());
    alert.setSignalType(
        candidate.ruleType() + ":" + candidate.threshold().stripTrailingZeros().toPlainString());
    alert.setLevel(candidate.threshold().abs().compareTo(BigDecimal.valueOf(7)) >= 0 ? 2 : 1);
    alert.setTitle(candidate.title());
    alert.setContent(candidate.content());
    alert.setTriggerPrice(quote.getLatestPrice());
    alert.setTriggerAt(LocalDateTime.now());
    alert.setChannels("serverchan");
    boolean sent = notificationService.sendServerChan(candidate.title(), candidate.content());
    alert.setPushed(sent ? 1 : 0);
    alert.setReadFlag(0);
    alertRepository.save(alert);
  }

  private BigDecimal averageTurnover(List<TradeStockDaily> records) {
    List<BigDecimal> values =
        records.stream()
            .map(TradeStockDaily::getTurnoverRate)
            .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0)
            .toList();
    if (values.isEmpty()) {
      return null;
    }
    BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
  }

  private String displayStockName(PotentialPool item, TradeStockBasic basic) {
    if (basic != null && basic.getStockName() != null && !basic.getStockName().isBlank()) {
      return basic.getStockName();
    }
    if (item.getStockName() != null && !item.getStockName().isBlank()) {
      return item.getStockName();
    }
    return item.getStockCode();
  }

  private TechAiAlertDTO toAlertDTO(InvestAlert alert) {
    return TechAiAlertDTO.builder()
        .id(alert.getId())
        .stockCode(alert.getStockCode())
        .signalType(alert.getSignalType())
        .title(alert.getTitle())
        .triggerPrice(alert.getTriggerPrice())
        .triggerAt(alert.getTriggerAt())
        .pushed(alert.getPushed() != null && alert.getPushed() == 1)
        .read(alert.getReadFlag() != null && alert.getReadFlag() == 1)
        .build();
  }

  private BigDecimal pctChange(BigDecimal value, BigDecimal base) {
    if (value == null || base == null || base.compareTo(BigDecimal.ZERO) == 0) return null;
    return value
        .subtract(base)
        .divide(base, 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private String resolveStockCode(String keyword) {
    String normalized = TechAiStockCodeUtils.normalizeProjectCode(keyword);
    TradeStockBasic exact = basic(normalized);
    if (exact != null) {
      return normalized;
    }
    if (!keyword.matches(".*\\d{6}.*")) {
      List<TradeStockBasic> byName = basicRepository.findByStockNameLike(keyword);
      if (!byName.isEmpty()) {
        return TechAiStockCodeUtils.normalizeProjectCode(byName.get(0).getStockCode());
      }
    }
    return normalized;
  }

  private TradeStockBasic basic(String stockCode) {
    for (String candidate : codeCandidates(List.of(stockCode))) {
      Optional<TradeStockBasic> basic = basicRepository.findByStockCode(candidate);
      if (basic.isPresent()) {
        return basic.get();
      }
    }
    return null;
  }

  private Map<String, TradeStockBasic> basics(Collection<String> codes) {
    Map<String, TradeStockBasic> result = new HashMap<>();
    for (TradeStockBasic basic : basicRepository.findByStockCodeIn(codeCandidates(codes))) {
      result.put(TechAiStockCodeUtils.normalizeProjectCode(basic.getStockCode()), basic);
    }
    return result;
  }

  private TradeStockBasic basicFromMap(Map<String, TradeStockBasic> basics, String stockCode) {
    return basics.get(TechAiStockCodeUtils.normalizeProjectCode(stockCode));
  }

  private Map<String, TechAiQuoteSnapshot> latestQuotes(Collection<String> codes) {
    if (codes.isEmpty()) {
      return Map.of();
    }
    List<String> normalizedCodes =
        codes.stream().map(TechAiStockCodeUtils::normalizeProjectCode).distinct().toList();
    List<String> candidates = codeCandidates(normalizedCodes);
    Map<String, TechAiQuoteSnapshot> result = new HashMap<>();

    for (TechAiQuoteSnapshot quote : quoteRepository.findLatestByStockCodes(candidates)) {
      putNewer(result, quote);
    }

    List<String> missing = missingCodes(normalizedCodes, result);
    if (!missing.isEmpty()) {
      for (TradeStockRealtimeQuote quote :
          realtimeQuoteRepository.findByStockCodeIn(codeCandidates(missing))) {
        putIfMissing(result, quoteToSnapshot(quote));
      }
    }

    missing = missingCodes(normalizedCodes, result);
    if (!missing.isEmpty()) {
      for (TradeStockRealtimeKline kline :
          realtimeKlineRepository.findLatestByStockCodesAndPeriod(codeCandidates(missing), "5m")) {
        putIfMissing(result, klineToSnapshot(kline));
      }
    }

    missing = missingCodes(normalizedCodes, result);
    if (!missing.isEmpty()) {
      sinaRealtimeQuoteService.fetch(missing).values().forEach(q -> putIfMissing(result, q));
    }

    missing = missingCodes(normalizedCodes, result);
    if (!missing.isEmpty()) {
      eastMoneyRealtimeQuoteService.fetch(missing).values().forEach(q -> putIfMissing(result, q));
    }

    missing = missingCodes(normalizedCodes, result);
    if (!missing.isEmpty()) {
      baostockMinuteQuoteService
          .fetchLatest5m(missing)
          .values()
          .forEach(q -> putIfMissing(result, q));
    }

    return result;
  }

  private List<String> missingCodes(List<String> codes, Map<String, TechAiQuoteSnapshot> quotes) {
    return codes.stream().filter(code -> !quotes.containsKey(code)).toList();
  }

  private void putNewer(Map<String, TechAiQuoteSnapshot> quotes, TechAiQuoteSnapshot quote) {
    if (quote == null || quote.getStockCode() == null || quote.getLatestPrice() == null) {
      return;
    }
    String key = TechAiStockCodeUtils.normalizeProjectCode(quote.getStockCode());
    TechAiQuoteSnapshot existing = quotes.get(key);
    if (existing == null
        || existing.getQuoteTime() == null
        || (quote.getQuoteTime() != null
            && quote.getQuoteTime().isAfter(existing.getQuoteTime()))) {
      quotes.put(key, quote);
    }
  }

  private void putIfMissing(Map<String, TechAiQuoteSnapshot> quotes, TechAiQuoteSnapshot quote) {
    if (quote == null || quote.getStockCode() == null || quote.getLatestPrice() == null) {
      return;
    }
    quotes.putIfAbsent(TechAiStockCodeUtils.normalizeProjectCode(quote.getStockCode()), quote);
  }

  private TechAiQuoteSnapshot quoteToSnapshot(TradeStockRealtimeQuote source) {
    TechAiQuoteSnapshot quote = new TechAiQuoteSnapshot();
    quote.setStockCode(source.getStockCode());
    quote.setQuoteTime(source.getQuoteTime());
    quote.setLatestPrice(source.getLatestPrice());
    quote.setPrevClosePrice(source.getLastClose());
    quote.setOpenPrice(source.getOpenPrice());
    quote.setVolume(source.getVolume());
    quote.setAmount(source.getAmount());
    quote.setTurnoverRate(source.getTurnoverRate());
    quote.setMinute5Time(source.getKlineTime5m());
    quote.setSource("realtime");
    return quote;
  }

  private TechAiQuoteSnapshot klineToSnapshot(TradeStockRealtimeKline source) {
    TechAiQuoteSnapshot quote = new TechAiQuoteSnapshot();
    quote.setStockCode(source.getStockCode());
    quote.setQuoteTime(source.getKlineTime());
    quote.setLatestPrice(source.getClosePrice());
    quote.setPrevClosePrice(source.getPreClose());
    quote.setOpenPrice(source.getOpenPrice());
    quote.setVolume(source.getVolume());
    quote.setAmount(source.getAmount());
    quote.setTurnoverRate(source.getTurnoverRate());
    quote.setMinute5OpenPrice(source.getOpenPrice());
    quote.setMinute5Time(source.getKlineTime());
    quote.setSource("realtime_5m");
    return quote;
  }

  private List<String> codeCandidates(Collection<String> codes) {
    List<String> result = new ArrayList<>();
    for (String code : codes) {
      String normalized = TechAiStockCodeUtils.normalizeProjectCode(code);
      result.add(normalized);
      result.add(TechAiStockCodeUtils.toQmtCode(normalized));
      int dot = normalized.indexOf('.');
      if (dot > 0) {
        result.add(normalized.substring(0, dot));
      }
    }
    return result.stream().distinct().toList();
  }

  private boolean isTradingTime() {
    LocalTime now = LocalTime.now();
    return (now.isAfter(LocalTime.of(9, 29)) && now.isBefore(LocalTime.of(11, 31)))
        || (now.isAfter(LocalTime.of(12, 59)) && now.isBefore(LocalTime.of(15, 1)));
  }

  private BigDecimal parsePositiveDecimal(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      BigDecimal decimal = new BigDecimal(value.trim());
      if (decimal.compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("阈值必须大于 0：" + field);
      }
      return decimal.setScale(2, RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("阈值格式错误：" + field);
    }
  }

  private Integer parsePositiveInteger(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed <= 0) {
        throw new IllegalArgumentException("数值必须大于 0：" + field);
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("数值格式错误：" + field);
    }
  }

  private Integer parseFlag(String value) {
    if (value == null) {
      return 0;
    }
    String v = value.trim().toLowerCase();
    return (v.equals("1") || v.equals("true") || v.equals("on") || v.equals("yes")) ? 1 : 0;
  }
}
