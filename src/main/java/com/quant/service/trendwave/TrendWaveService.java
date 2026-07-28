package com.quant.service.trendwave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.TrendWaveProperties;
import com.quant.dto.trendwave.*;
import com.quant.entity.*;
import com.quant.repository.*;
import com.quant.service.StockQueryService;
import com.quant.service.aistockdata.AStockDataQuoteService;
import com.quant.service.notification.NotificationService;
import com.quant.service.technical.LimitUpDetector;
import com.quant.service.technical.MovingAverageCalculator;
import com.quant.service.technical.MovingAverageCalculator.MovingAverages;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrendWaveService {

  private static final Set<String> SCAN_STATUSES =
      Set.of(
          "SCREENING",
          "WATCH_PULLBACK",
          "WATCH_BREAKOUT",
          "BUY_SIGNAL",
          "HOLDING",
          "PARTIAL_EXIT");
  private static final Set<String> OPEN_POS = Set.of("HOLDING", "PARTIAL_EXIT");

  private final TrendWaveProperties props;
  private final MoneyStockPoolRepository poolRepository;
  private final MoneyWatchRepository watchRepository;
  private final MoneySetupRepository setupRepository;
  private final MoneyPositionRepository positionRepository;
  private final MoneyEventRepository eventRepository;
  private final MoneyTradeLegRepository tradeLegRepository;
  private final MoneyDailyMetricsRepository metricsRepository;
  private final TradeStockDailyRepository dailyRepository;
  private final StockQueryService stockQueryService;
  private final AStockDataQuoteService quoteService;
  private final NotificationService notificationService;
  private final TrendWaveRuleEngine ruleEngine;
  private final TrendWaveSetupDetector setupDetector;
  private final LimitUpDetector limitUpDetector;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicBoolean scanning = new AtomicBoolean(false);

  // ───────── Pool CRUD ─────────

  @Transactional
  public MoneyPoolItemDTO addToPool(MoneyPoolAddRequest req, Long userId) {
    if (req == null || req.getStockCode() == null || req.getStockCode().isBlank()) {
      throw new IllegalArgumentException("stockCode 必填");
    }
    TradeStockBasic basic =
        stockQueryService
            .resolveStock(req.getStockCode().trim())
            .orElseThrow(() -> new IllegalArgumentException("无法识别股票: " + req.getStockCode()));

    Optional<MoneyStockPool> existing =
        poolRepository.findByUserIdAndStockCode(userId, basic.getStockCode());
    MoneyStockPool pool =
        existing.orElseGet(
            () -> {
              MoneyStockPool p = new MoneyStockPool();
              p.setUserId(userId);
              p.setStockCode(basic.getStockCode());
              return p;
            });
    pool.setStockName(basic.getStockName());
    pool.setSectorTag(
        req.getSectorTag() != null && !req.getSectorTag().isBlank()
            ? req.getSectorTag().trim()
            : inferSectorTag(basic));
    pool.setSource(
        req.getSource() == null || req.getSource().isBlank()
            ? "MANUAL"
            : req.getSource().trim().toUpperCase());
    pool.setStatus("ACTIVE");
    pool.setPaperMode(Boolean.TRUE.equals(req.getPaperMode()) ? 1 : 0);
    if (req.getMemo() != null) {
      pool.setMemo(req.getMemo());
    }
    pool = poolRepository.save(pool);

    MoneyWatch watch = ensureActiveWatch(pool);
    rescreenWatch(watch.getId());
    return toPoolDto(pool, watch);
  }

  @Transactional(readOnly = true)
  public List<MoneyPoolItemDTO> listPool(Long userId) {
    List<MoneyStockPool> pools =
        userId == null
            ? poolRepository.findByStatusOrderByUpdatedAtDesc("ACTIVE")
            : poolRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, "ACTIVE");
    List<MoneyPoolItemDTO> out = new ArrayList<>();
    for (MoneyStockPool p : pools) {
      MoneyWatch w =
          watchRepository.findByPoolIdAndActiveFlag(p.getId(), 1).orElse(null);
      out.add(toPoolDto(p, w));
    }
    return out;
  }

  @Transactional
  public void removeFromPool(Long poolId, Long userId) {
    MoneyStockPool pool =
        poolRepository
            .findById(poolId)
            .orElseThrow(() -> new IllegalArgumentException("池条目不存在"));
    if (userId != null && userId > 0 && !Objects.equals(pool.getUserId(), userId)) {
      throw new IllegalArgumentException("无权操作该池条目");
    }
    pool.setStatus("REMOVED");
    poolRepository.save(pool);
    watchRepository
        .findByPoolIdAndActiveFlag(poolId, 1)
        .ifPresent(
            w -> {
              if (!OPEN_POS.contains(w.getStatus())) {
                w.setActiveFlag(0);
                w.setStatus("CLOSED");
                w.setInvalidReason("池条目移除");
                watchRepository.save(w);
              }
            });
  }

  // ───────── Watch ─────────

  @Transactional(readOnly = true)
  public List<MoneyWatchDTO> listWatches(Long userId) {
    List<MoneyWatch> watches =
        userId == null
            ? watchRepository.findByActiveFlagOrderByUpdatedAtDesc(1)
            : watchRepository.findByUserIdAndActiveFlagOrderByUpdatedAtDesc(userId, 1);
    return enrichWatches(watches);
  }

  @Transactional(readOnly = true)
  public MoneyWatchDTO getWatch(Long id) {
    MoneyWatch w =
        watchRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("监控不存在"));
    List<MoneyWatchDTO> list = enrichWatches(List.of(w));
    return list.isEmpty() ? null : list.get(0);
  }

  @Transactional
  public MoneyWatchDTO rescreenWatch(Long watchId) {
    MoneyWatch watch =
        watchRepository
            .findById(watchId)
            .orElseThrow(() -> new IllegalArgumentException("监控不存在"));
    List<TradeStockDaily> daily =
        dailyRepository.findRecentKlineBatch(
            List.of(watch.getStockCode()), LocalDate.now().minusDays(900), 800);
    List<TradeStockDaily> asc = MovingAverageCalculator.sortedAsc(daily);
    MovingAverages mas = MovingAverageCalculator.fromDaily(asc);
    TradeStockBasic basic =
        stockQueryService.resolveStock(watch.getStockCode()).orElse(null);
    boolean sectorOk = isSectorOk(watch.getSectorTag(), basic);
    boolean valuationOk = isValuationOk(basic);
    BigDecimal highNear = MovingAverageCalculator.highNearRatio(asc, 756);
    Map<String, Object> detail =
        ruleEngine.screenDetail(
            mas, highNear, sectorOk, valuationOk, basic == null ? null : basic.getPeTtm());
    watch.setScreenDetail(toJson(detail));
    boolean passed = Boolean.TRUE.equals(detail.get("passed"));
    watch.setScreenPassed(passed ? 1 : 0);

    MarketEnv env = resolveMarketEnv();
    watch.setMarketRegime(env.regime());
    watch.setIndexAboveMa20(env.aboveMa20() ? 1 : 0);

    persistMetrics(watch.getStockCode(), watch.getStockName(), asc, mas);

    if (!passed && isWatching(watch.getStatus())) {
      watch.setStatus("SCREENING");
    }

    // 检测 setup
    if (passed) {
      refreshSetups(watch, asc);
      String next = chooseWatchStatus(watch);
      if (isWatching(watch.getStatus()) || "SCREENING".equals(watch.getStatus())) {
        watch.setStatus(next);
      }
    }
    watchRepository.save(watch);
    return getWatch(watchId);
  }

  // ───────── Position / Trades ─────────

  @Transactional
  public MoneyPositionDTO openPosition(MoneyPositionOpenRequest req, Long userId) {
    if (req == null || req.getWatchId() == null || req.getPrice() == null) {
      throw new IllegalArgumentException("watchId/price 必填");
    }
    MoneyWatch watch =
        watchRepository
            .findById(req.getWatchId())
            .orElseThrow(() -> new IllegalArgumentException("监控不存在"));
    if (!"BUY_SIGNAL".equals(watch.getStatus())
        && !"WATCH_PULLBACK".equals(watch.getStatus())
        && !"WATCH_BREAKOUT".equals(watch.getStatus())) {
      // 允许在信号或观察状态手动建仓
    }
    long openCount = positionRepository.countByUserIdAndStatusIn(userId, OPEN_POS);
    if (openCount >= props.getMaxPositions()) {
      throw new IllegalStateException("同时持仓不得超过 " + props.getMaxPositions() + " 只");
    }
    if (positionRepository.findByWatchId(watch.getId()).isPresent()) {
      throw new IllegalStateException("该监控已有持仓");
    }

    String buyType =
        watch.getBuySignalType() != null
            ? watch.getBuySignalType()
            : ("WATCH_BREAKOUT".equals(watch.getStatus()) ? "BREAKOUT" : "PULLBACK");
    MoneySetup setup =
        setupRepository
            .findFirstByWatchIdAndSetupTypeAndStatus(watch.getId(), buyType, "TRIGGERED")
            .or(() -> setupRepository.findFirstByWatchIdAndSetupTypeAndStatus(watch.getId(), buyType, "ACTIVE"))
            .orElse(null);

    BigDecimal entry = req.getPrice().setScale(2, RoundingMode.HALF_UP);
    MoneyPosition pos = new MoneyPosition();
    pos.setWatchId(watch.getId());
    pos.setPoolId(watch.getPoolId());
    pos.setUserId(userId);
    pos.setStockCode(watch.getStockCode());
    pos.setStockName(watch.getStockName());
    pos.setBuyType(buyType);
    pos.setEntryPrice(entry);
    pos.setEntryDate(req.getTradeDate() == null ? LocalDateTime.now() : req.getTradeDate());
    pos.setEntryShares(req.getShares());
    pos.setPositionPct(BigDecimal.valueOf(100));
    pos.setPeakPrice(entry);
    pos.setProfitTier("T0");
    pos.setStopPrimary(ruleEngine.calcStopPrimary(buyType, setup, entry));
    pos.setStopSecondary(ruleEngine.calcStopSecondary(buyType, entry));
    pos.setStatus("HOLDING");
    pos = positionRepository.save(pos);

    MoneyTradeLeg leg = new MoneyTradeLeg();
    leg.setPositionId(pos.getId());
    leg.setWatchId(watch.getId());
    leg.setStockCode(watch.getStockCode());
    leg.setLegType("BUY");
    leg.setPrice(entry);
    leg.setShares(req.getShares());
    if (req.getShares() != null) {
      leg.setAmount(entry.multiply(BigDecimal.valueOf(req.getShares())));
    }
    leg.setTradeDate(pos.getEntryDate());
    leg.setSource("MANUAL");
    leg.setMemo(req.getMemo());
    tradeLegRepository.save(leg);

    watch.setStatus("HOLDING");
    watch.setBuySignalAt(null);
    watchRepository.save(watch);

    saveEvent(
        watch,
        pos,
        "POSITION_OPENED",
        "INFO",
        watch.getStockName() + " 建仓确认",
        "买入价 " + entry + "，买点类型 " + buyType,
        entry,
        false);

    return toPositionDto(pos, entry);
  }

  @Transactional
  public MoneyPositionDTO addTradeLeg(MoneyTradeLegRequest req, Long userId) {
    if (req == null || req.getPositionId() == null || req.getPrice() == null || req.getLegType() == null) {
      throw new IllegalArgumentException("positionId/price/legType 必填");
    }
    MoneyPosition pos =
        positionRepository
            .findById(req.getPositionId())
            .orElseThrow(() -> new IllegalArgumentException("持仓不存在"));
    if (!OPEN_POS.contains(pos.getStatus())) {
      throw new IllegalStateException("持仓已关闭");
    }
    String type = req.getLegType().trim().toUpperCase();
    BigDecimal price = req.getPrice().setScale(2, RoundingMode.HALF_UP);
    LocalDateTime tradeDate = req.getTradeDate() == null ? LocalDateTime.now() : req.getTradeDate();

    if ("ADD".equals(type)) {
      if (integerTrue(pos.getAddPositionDone())) {
        throw new IllegalStateException("已加仓一次，不可再加");
      }
      int baseShares = pos.getEntryShares() == null ? 100 : pos.getEntryShares();
      int addShares =
          req.getShares() != null
              ? req.getShares()
              : BigDecimal.valueOf(baseShares)
                  .multiply(props.getAddPosition().getMaxAddRatio())
                  .intValue();
      pos.setAddPositionDone(1);
      pos.setAddEntryPrice(price);
      pos.setAddShares(addShares);
      pos.setCostStop(effectiveEntry(pos)); // 加仓后止损上移至成本
      pos.setStopPrimary(max(pos.getStopPrimary(), pos.getCostStop()));
      positionRepository.save(pos);
      saveLeg(pos, "ADD", price, addShares, tradeDate, "MANUAL", req.getLinkedEventId(), req.getMemo());
      return toPositionDto(pos, price);
    }

    if ("SELL".equals(type)) {
      BigDecimal sellPct =
          req.getSellPct() != null ? req.getSellPct() : BigDecimal.valueOf(100);
      BigDecimal remain =
          pos.getPositionPct().subtract(sellPct).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
      Integer sellShares = req.getShares();
      if ((sellShares == null || sellShares <= 0) && pos.getEntryShares() != null) {
        sellShares =
            BigDecimal.valueOf(totalShares(pos))
                .multiply(sellPct)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .intValue();
      }
      saveLeg(pos, "SELL", price, sellShares, tradeDate, "MANUAL", req.getLinkedEventId(), req.getMemo());

      if (remain.compareTo(BigDecimal.ZERO) <= 0) {
        closePosition(pos, price, req.getMemo() == null ? "MANUAL_SELL" : req.getMemo());
      } else {
        pos.setPositionPct(remain);
        pos.setStatus("PARTIAL_EXIT");
        // 半仓后 trailing 上移
        if (pos.getPeakPrice() != null) {
          pos.setTrailingStop(ruleEngine.trailingStopPrice(pos.getPeakPrice(), pos.getProfitTier()));
          pos.setCostStop(effectiveEntry(pos));
        }
        positionRepository.save(pos);
        MoneyWatch watch = watchRepository.findById(pos.getWatchId()).orElse(null);
        if (watch != null) {
          watch.setStatus("PARTIAL_EXIT");
          watchRepository.save(watch);
        }
      }
      return toPositionDto(pos, price);
    }
    throw new IllegalArgumentException("不支持的 legType: " + type);
  }

  // ───────── Events / Scan ─────────

  @Transactional(readOnly = true)
  public List<MoneyEventDTO> listEvents(int limit) {
    return eventRepository.findTop100ByOrderByCreatedAtDesc().stream()
        .limit(Math.min(limit, 100))
        .map(this::toEventDto)
        .toList();
  }

  @Transactional
  public void ackEvent(Long eventId) {
    eventRepository
        .findById(eventId)
        .ifPresent(
            e -> {
              e.setAcknowledged(1);
              eventRepository.save(e);
            });
  }

  @Transactional
  public MoneyScanResultDTO scan(boolean eod) {
    if (!props.isEnabled()) {
      return MoneyScanResultDTO.builder()
          .mode(eod ? "EOD" : "INTRADAY")
          .scanned(0)
          .signals(0)
          .pushed(0)
          .message("trend-wave disabled")
          .ranAt(LocalDateTime.now())
          .build();
    }
    if (!scanning.compareAndSet(false, true)) {
      return MoneyScanResultDTO.builder()
          .mode(eod ? "EOD" : "INTRADAY")
          .message("scan already running")
          .ranAt(LocalDateTime.now())
          .build();
    }
    try {
      if (!eod && props.isRequireTradingTime() && !isTradingTime(LocalDateTime.now())) {
        return MoneyScanResultDTO.builder()
            .mode("INTRADAY")
            .message("outside trading time")
            .ranAt(LocalDateTime.now())
            .build();
      }
      List<MoneyWatch> watches =
          watchRepository.findByActiveFlagAndStatusIn(1, SCAN_STATUSES);
      if (watches.isEmpty()) {
        return MoneyScanResultDTO.builder()
            .mode(eod ? "EOD" : "INTRADAY")
            .scanned(0)
            .message("no active watches")
            .ranAt(LocalDateTime.now())
            .build();
      }

      List<String> codes =
          watches.stream().map(MoneyWatch::getStockCode).distinct().toList();
      Map<String, AStockDataQuoteService.QuoteSnapshot> quotes = quoteService.fetchQuotes(codes);
      Map<String, List<TradeStockDaily>> dailyMap = loadDaily(codes);
      Map<Long, MoneyStockPool> poolMap =
          poolRepository
              .findAllById(watches.stream().map(MoneyWatch::getPoolId).distinct().toList())
              .stream()
              .collect(Collectors.toMap(MoneyStockPool::getId, p -> p));
      Map<Long, List<MoneySetup>> setupMap = new HashMap<>();
      for (MoneySetup s :
          setupRepository.findByWatchIdInAndStatus(
              watches.stream().map(MoneyWatch::getId).toList(), "ACTIVE")) {
        setupMap.computeIfAbsent(s.getWatchId(), k -> new ArrayList<>()).add(s);
      }
      // also include TRIGGERED for holding stop calc context
      for (MoneySetup s :
          setupRepository.findByWatchIdInAndStatus(
              watches.stream().map(MoneyWatch::getId).toList(), "TRIGGERED")) {
        setupMap.computeIfAbsent(s.getWatchId(), k -> new ArrayList<>()).add(s);
      }
      Map<Long, MoneyPosition> posMap =
          positionRepository.findByStatusIn(OPEN_POS).stream()
              .collect(Collectors.toMap(MoneyPosition::getWatchId, p -> p, (a, b) -> a));

      MarketEnv env = resolveMarketEnv();
      int signals = 0;
      int pushed = 0;
      LocalDateTime now = LocalDateTime.now();

      for (MoneyWatch watch : watches) {
        try {
          if (watch.getPausedUntil() != null && !watch.getPausedUntil().isBefore(LocalDate.now())) {
            continue;
          }
          List<TradeStockDaily> daily = dailyMap.getOrDefault(watch.getStockCode(), List.of());
          if (daily.isEmpty()) continue;
          List<TradeStockDaily> asc = MovingAverageCalculator.sortedAsc(daily);
          MovingAverages mas = MovingAverageCalculator.fromDaily(asc);
          AStockDataQuoteService.QuoteSnapshot q = quotes.get(normalize(watch.getStockCode()));
          if (q == null) {
            // try raw
            q = quotes.get(watch.getStockCode());
          }
          BigDecimal latest =
              q != null && q.latestPrice() != null
                  ? q.latestPrice()
                  : mas.latestClose();
          if (latest == null) continue;

          if (eod) {
            // EOD 重筛 + setup 刷新
            TradeStockBasic basic =
                stockQueryService.resolveStock(watch.getStockCode()).orElse(null);
            Map<String, Object> detail =
                ruleEngine.screenDetail(
                    mas,
                    MovingAverageCalculator.highNearRatio(asc, 756),
                    isSectorOk(watch.getSectorTag(), basic),
                    isValuationOk(basic),
                    basic == null ? null : basic.getPeTtm());
            watch.setScreenDetail(toJson(detail));
            watch.setScreenPassed(Boolean.TRUE.equals(detail.get("passed")) ? 1 : 0);
            watch.setMarketRegime(env.regime());
            watch.setIndexAboveMa20(env.aboveMa20() ? 1 : 0);
            persistMetrics(watch.getStockCode(), watch.getStockName(), asc, mas);
            if (Boolean.TRUE.equals(detail.get("passed")) && isWatching(watch.getStatus())) {
              refreshSetups(watch, asc);
              watch.setStatus(chooseWatchStatus(watch));
            }
          }

          MoneyPosition pos = posMap.get(watch.getId());
          if (pos != null) {
            updatePositionDynamics(pos, latest, mas, eod);
          }

          TradeStockDaily lastBar = asc.get(asc.size() - 1);
          // 腾讯快照无开高低量，盘中用最新日K近似；EOD 用日K收盘价覆盖 latest
          BigDecimal todayOpen = lastBar.getOpenPrice();
          BigDecimal todayHigh = lastBar.getHighPrice();
          BigDecimal todayLow = lastBar.getLowPrice();
          Long todayVolume = lastBar.getVolume();
          if (eod && lastBar.getClosePrice() != null) {
            latest = lastBar.getClosePrice();
          }

          TrendWaveContext ctx =
              TrendWaveContext.builder()
                  .pool(poolMap.get(watch.getPoolId()))
                  .watch(watch)
                  .setups(setupMap.getOrDefault(watch.getId(), List.of()))
                  .position(pos)
                  .dailyAsc(asc)
                  .mas(mas)
                  .latestPrice(latest)
                  .todayOpen(todayOpen)
                  .todayHigh(todayHigh)
                  .todayLow(todayLow)
                  .todayVolume(todayVolume)
                  .eodScan(eod)
                  .indexAboveMa20(env.aboveMa20())
                  .marketRegime(env.regime())
                  .now(now)
                  .build();

          List<TrendWaveSignal> sigs = ruleEngine.evaluate(ctx);
          for (TrendWaveSignal sig : sigs) {
            if (!shouldEmit(watch, sig, now)) continue;
            signals++;
            boolean didPush = dispatchSignal(watch, pos, sig, ctx);
            if (didPush) pushed++;
          }
          watchRepository.save(watch);
          if (pos != null) positionRepository.save(pos);
        } catch (Exception ex) {
          log.warn("trend-wave scan failed for {}: {}", watch.getStockCode(), ex.getMessage());
        }
      }

      return MoneyScanResultDTO.builder()
          .mode(eod ? "EOD" : "INTRADAY")
          .scanned(watches.size())
          .signals(signals)
          .pushed(pushed)
          .message(String.format("扫描 %d，信号 %d，推送 %d", watches.size(), signals, pushed))
          .ranAt(now)
          .build();
    } finally {
      scanning.set(false);
    }
  }

  // ───────── internals ─────────

  private MoneyWatch ensureActiveWatch(MoneyStockPool pool) {
    return watchRepository
        .findByPoolIdAndActiveFlag(pool.getId(), 1)
        .orElseGet(
            () -> {
              long active =
                  watchRepository.countByUserIdAndActiveFlagAndStatusIn(
                      pool.getUserId(),
                      1,
                      Set.of(
                          "SCREENING",
                          "WATCH_PULLBACK",
                          "WATCH_BREAKOUT",
                          "BUY_SIGNAL",
                          "HOLDING",
                          "PARTIAL_EXIT"));
              if (active >= props.getMaxActiveWatches()) {
                throw new IllegalStateException(
                    "同时监控不得超过 " + props.getMaxActiveWatches() + " 只");
              }
              MoneyWatch w = new MoneyWatch();
              w.setPoolId(pool.getId());
              w.setUserId(pool.getUserId());
              w.setStockCode(pool.getStockCode());
              w.setStockName(pool.getStockName());
              w.setSectorTag(pool.getSectorTag());
              w.setStatus("SCREENING");
              w.setActiveFlag(1);
              w.setMemo(pool.getMemo());
              return watchRepository.save(w);
            });
  }

  private void refreshSetups(MoneyWatch watch, List<TradeStockDaily> asc) {
    // expire old active if new detection differs
    List<MoneySetup> actives = setupRepository.findByWatchIdAndStatus(watch.getId(), "ACTIVE");
    MoneySetup pullback = setupDetector.detectPullbackSetup(watch, asc);
    MoneySetup breakout = setupDetector.detectBreakoutSetup(watch, asc);

    boolean hasPullback =
        actives.stream().anyMatch(s -> "PULLBACK".equals(s.getSetupType()));
    boolean hasBreakout =
        actives.stream().anyMatch(s -> "BREAKOUT".equals(s.getSetupType()));

    if (pullback != null && !hasPullback) {
      setupRepository.save(pullback);
    } else if (pullback != null) {
      actives.stream()
          .filter(s -> "PULLBACK".equals(s.getSetupType()))
          .findFirst()
          .ifPresent(
              s -> {
                s.setLimitUpCount(pullback.getLimitUpCount());
                s.setPlatformLow(pullback.getPlatformLow());
                s.setPlatformOpen(pullback.getPlatformOpen());
                s.setLimitUpVolume(pullback.getLimitUpVolume());
                s.setLimitUpDates(pullback.getLimitUpDates());
                setupRepository.save(s);
              });
    }
    if (breakout != null && !hasBreakout) {
      setupRepository.save(breakout);
    } else if (breakout != null) {
      actives.stream()
          .filter(s -> "BREAKOUT".equals(s.getSetupType()))
          .findFirst()
          .ifPresent(
              s -> {
                s.setPlatformHigh(breakout.getPlatformHigh());
                s.setPlatformDays(breakout.getPlatformDays());
                setupRepository.save(s);
              });
    }
  }

  private String chooseWatchStatus(MoneyWatch watch) {
    List<MoneySetup> actives = setupRepository.findByWatchIdAndStatus(watch.getId(), "ACTIVE");
    boolean pb = actives.stream().anyMatch(s -> "PULLBACK".equals(s.getSetupType()));
    boolean bo = actives.stream().anyMatch(s -> "BREAKOUT".equals(s.getSetupType()));
    if (pb) return "WATCH_PULLBACK"; // 优先回踩
    if (bo) return "WATCH_BREAKOUT";
    return integerTrue(watch.getScreenPassed()) ? "SCREENING" : "SCREENING";
  }

  private boolean dispatchSignal(
      MoneyWatch watch, MoneyPosition pos, TrendWaveSignal sig, TrendWaveContext ctx) {
    MoneyEvent event = new MoneyEvent();
    event.setWatchId(watch.getId());
    event.setPositionId(pos == null ? null : pos.getId());
    event.setPoolId(watch.getPoolId());
    event.setStockCode(watch.getStockCode());
    event.setStockName(watch.getStockName());
    event.setEventType(sig.getEventType());
    event.setSeverity(sig.getSeverity());
    event.setTitle(sig.getTitle());
    event.setContent(sig.getContent());
    event.setTriggerPrice(sig.getTriggerPrice());
    event.setTriggerData(toJson(sig.getTriggerData()));
    event = eventRepository.save(event);

    if (sig.isMutateState()) {
      applyStateMutation(watch, pos, sig, ctx, event);
    }

    boolean pushed = false;
    if ("ACTION".equals(sig.getSeverity()) || "WARN".equals(sig.getSeverity())) {
      String md =
          (sig.getContent() == null ? "" : sig.getContent())
              + "\n\n触发价: "
              + sig.getTriggerPrice()
              + "\n时间: "
              + LocalDateTime.now()
              + "\n状态: "
              + watch.getStatus();
      pushed = notificationService.sendServerChan(sig.getTitle(), md);
      event.setPushed(pushed ? 1 : 0);
      eventRepository.save(event);
    }
    return pushed;
  }

  private void applyStateMutation(
      MoneyWatch watch,
      MoneyPosition pos,
      TrendWaveSignal sig,
      TrendWaveContext ctx,
      MoneyEvent event) {
    if (sig.getSetupId() != null && sig.getNextSetupStatus() != null) {
      setupRepository
          .findById(sig.getSetupId())
          .ifPresent(
              s -> {
                s.setStatus(sig.getNextSetupStatus());
                s.setTriggerPrice(sig.getTriggerPrice());
                s.setTriggerAt(LocalDateTime.now());
                setupRepository.save(s);
              });
    }
    if ("BUY_SIGNAL".equals(sig.getNextWatchStatus())) {
      watch.setStatus("BUY_SIGNAL");
      watch.setBuySignalType(
          sig.getEventType() != null && sig.getEventType().contains("BREAKOUT")
              ? "BREAKOUT"
              : "PULLBACK");
      watch.setBuySignalAt(LocalDateTime.now());
      watch.setBuySignalPrice(sig.getTriggerPrice());
      watch.setSignalExpireAt(
          LocalDateTime.now().plusDays(props.getBuySignalExpireDays()));
      // paper auto open
      if (sig.isPaperAutoExecute() && pos == null) {
        autoPaperOpen(watch, sig, event);
      }
    } else if ("INVALID".equals(sig.getNextWatchStatus())) {
      watch.setStatus("INVALID");
      watch.setInvalidReason(sig.getCloseReason() == null ? sig.getEventType() : sig.getCloseReason());
      watch.setActiveFlag(0);
      expireActiveSetups(watch.getId());
    } else if ("CLOSED".equals(sig.getNextWatchStatus()) && pos != null) {
      if (sig.isPaperAutoExecute()) {
        autoPaperClose(watch, pos, sig, event);
      } else {
        // 仅标记待卖出，等用户确认；仍更新提示状态
        watch.setStatus("PARTIAL_EXIT".equals(pos.getStatus()) ? "PARTIAL_EXIT" : "HOLDING");
      }
    } else if ("PARTIAL_EXIT".equals(sig.getNextWatchStatus()) && pos != null) {
      if (sig.isPaperAutoExecute()) {
        autoPaperPartial(watch, pos, sig, event);
      } else {
        // 推送等待用户确认，不强制改仓位
      }
    } else if (sig.getNextWatchStatus() != null) {
      watch.setStatus(sig.getNextWatchStatus());
    }
  }

  private void autoPaperOpen(MoneyWatch watch, TrendWaveSignal sig, MoneyEvent event) {
    try {
      MoneyPositionOpenRequest req = new MoneyPositionOpenRequest();
      req.setWatchId(watch.getId());
      req.setPrice(sig.getTriggerPrice());
      req.setShares(100);
      req.setMemo("SYSTEM_PAPER");
      MoneyPositionDTO dto = openPosition(req, watch.getUserId());
      MoneyPosition pos = positionRepository.findById(dto.getId()).orElse(null);
      if (pos != null) {
        List<MoneyTradeLeg> legs = tradeLegRepository.findByPositionIdOrderByTradeDateAsc(pos.getId());
        for (MoneyTradeLeg leg : legs) {
          leg.setSource("SYSTEM_PAPER");
          leg.setLinkedEventId(event.getId());
          tradeLegRepository.save(leg);
        }
      }
    } catch (Exception e) {
      log.warn("paper open failed: {}", e.getMessage());
    }
  }

  private void autoPaperClose(
      MoneyWatch watch, MoneyPosition pos, TrendWaveSignal sig, MoneyEvent event) {
    MoneyTradeLegRequest req = new MoneyTradeLegRequest();
    req.setPositionId(pos.getId());
    req.setLegType("SELL");
    req.setPrice(sig.getTriggerPrice());
    req.setSellPct(BigDecimal.valueOf(100));
    req.setLinkedEventId(event.getId());
    req.setMemo(sig.getEventType());
    addTradeLeg(req, watch.getUserId());
    List<MoneyTradeLeg> legs = tradeLegRepository.findByPositionIdOrderByTradeDateAsc(pos.getId());
    legs.stream()
        .filter(l -> "SELL".equals(l.getLegType()))
        .reduce((a, b) -> b)
        .ifPresent(
            l -> {
              l.setSource("SYSTEM_PAPER");
              tradeLegRepository.save(l);
            });
    // consecutive stops
    if (sig.getEventType() != null && sig.getEventType().startsWith("STOP")) {
      int n = watch.getConsecutiveStops() == null ? 0 : watch.getConsecutiveStops();
      watch.setConsecutiveStops(n + 1);
      if (watch.getConsecutiveStops() >= 2) {
        watch.setPausedUntil(LocalDate.now().plusDays(props.getPauseDaysAfterTwoStops()));
        watch.setConsecutiveStops(0);
      }
    } else {
      watch.setConsecutiveStops(0);
    }
  }

  private void autoPaperPartial(
      MoneyWatch watch, MoneyPosition pos, TrendWaveSignal sig, MoneyEvent event) {
    BigDecimal targetPct =
        sig.getNextPositionPct() == null ? BigDecimal.valueOf(50) : sig.getNextPositionPct();
    BigDecimal sellPct = pos.getPositionPct().subtract(targetPct).max(BigDecimal.ZERO);
    if (sellPct.compareTo(BigDecimal.ZERO) <= 0) return;
    MoneyTradeLegRequest req = new MoneyTradeLegRequest();
    req.setPositionId(pos.getId());
    req.setLegType("SELL");
    req.setPrice(sig.getTriggerPrice());
    req.setSellPct(sellPct);
    req.setLinkedEventId(event.getId());
    req.setMemo(sig.getEventType());
    addTradeLeg(req, watch.getUserId());
  }

  private void closePosition(MoneyPosition pos, BigDecimal exitPrice, String reason) {
    pos.setStatus("CLOSED");
    pos.setPositionPct(BigDecimal.ZERO);
    pos.setClosedAt(LocalDateTime.now());
    pos.setCloseReason(reason);
    BigDecimal entry = effectiveEntry(pos);
    BigDecimal pnlPct =
        exitPrice
            .subtract(entry)
            .divide(entry, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP);
    pos.setRealizedPnlPct(pnlPct);
    if (pos.getEntryShares() != null) {
      pos.setRealizedPnl(
          exitPrice
              .subtract(entry)
              .multiply(BigDecimal.valueOf(totalShares(pos)))
              .setScale(2, RoundingMode.HALF_UP));
    }
    positionRepository.save(pos);
    watchRepository
        .findById(pos.getWatchId())
        .ifPresent(
            w -> {
              w.setStatus("CLOSED");
              w.setActiveFlag(0);
              watchRepository.save(w);
            });
  }

  private void updatePositionDynamics(
      MoneyPosition pos, BigDecimal latest, MovingAverages mas, boolean eod) {
    if (pos.getPeakPrice() == null || latest.compareTo(pos.getPeakPrice()) > 0) {
      pos.setPeakPrice(latest);
    }
    BigDecimal entry = effectiveEntry(pos);
    BigDecimal profitPct =
        latest.subtract(entry).divide(entry, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    String tier = ruleEngine.resolveTier(profitPct, props.getTakeProfit());
    pos.setProfitTier(tier);
    pos.setTrailingStop(ruleEngine.trailingStopPrice(pos.getPeakPrice(), tier));
    if (!"T0".equals(tier)) {
      pos.setCostStop(entry);
      // T2 锁定 20% 利润
      if ("T2".equals(tier) || "T3".equals(tier)) {
        BigDecimal lock =
            entry.multiply(BigDecimal.valueOf(1.20)).setScale(2, RoundingMode.HALF_UP);
        pos.setCostStop(lock);
        if (pos.getStopPrimary() == null || pos.getStopPrimary().compareTo(lock) < 0) {
          pos.setStopPrimary(lock);
        }
      }
    }
    if (eod && mas != null) {
      if (!mas.aboveMa20()) {
        pos.setBelowMa20Days((pos.getBelowMa20Days() == null ? 0 : pos.getBelowMa20Days()) + 1);
      } else {
        pos.setBelowMa20Days(0);
      }
    }
  }

  private boolean shouldEmit(MoneyWatch watch, TrendWaveSignal sig, LocalDateTime now) {
    if ("ACTION".equals(sig.getSeverity())
        && sig.getEventType() != null
        && (sig.getEventType().startsWith("STOP") || sig.getEventType().startsWith("TP_"))) {
      return true;
    }
    Optional<MoneyEvent> latest =
        eventRepository.findFirstByWatchIdAndEventTypeOrderByCreatedAtDesc(
            watch.getId(), sig.getEventType());
    if (latest.isEmpty()) return true;
    return latest
        .get()
        .getCreatedAt()
        .plusMinutes(props.getCooldownMinutes())
        .isBefore(now);
  }

  private void expireActiveSetups(Long watchId) {
    for (MoneySetup s : setupRepository.findByWatchIdAndStatus(watchId, "ACTIVE")) {
      s.setStatus("EXPIRED");
      setupRepository.save(s);
    }
  }

  private Map<String, List<TradeStockDaily>> loadDaily(List<String> codes) {
    Map<String, List<TradeStockDaily>> map = new HashMap<>();
    if (codes.isEmpty()) return map;
    for (TradeStockDaily d :
        dailyRepository.findRecentKlineBatch(codes, LocalDate.now().minusDays(900), 800)) {
      map.computeIfAbsent(d.getStockCode(), k -> new ArrayList<>()).add(d);
    }
    return map;
  }

  private void persistMetrics(
      String code, String name, List<TradeStockDaily> asc, MovingAverages mas) {
    if (asc.isEmpty() || mas == null) return;
    TradeStockDaily last = asc.get(asc.size() - 1);
    MoneyDailyMetrics m =
        metricsRepository
            .findByStockCodeAndTradeDate(code, last.getTradeDate())
            .orElseGet(MoneyDailyMetrics::new);
    m.setStockCode(code);
    m.setTradeDate(last.getTradeDate());
    m.setMa5(mas.ma5());
    m.setMa10(mas.ma10());
    m.setMa20(mas.ma20());
    m.setMa60(mas.ma60());
    m.setMa20Slope(mas.ma20Slope());
    m.setVolMa5(mas.volMa5());
    m.setVolMa20(mas.volMa20());
    m.setVolRatio(mas.volRatio());
    m.setClosePrice(last.getClosePrice());
    m.setVolume(last.getVolume());
    if (asc.size() >= 2) {
      m.setIsLimitUp(
          limitUpDetector.isLimitUp(last, asc.get(asc.size() - 2), code, name) ? 1 : 0);
    }
    metricsRepository.save(m);
  }

  private MarketEnv resolveMarketEnv() {
    try {
      Map<String, AStockDataQuoteService.QuoteSnapshot> q =
          quoteService.fetchQuotes(List.of(props.getMarketIndexCode()));
      AStockDataQuoteService.QuoteSnapshot snap =
          q.values().stream().findFirst().orElse(null);
      if (snap == null || snap.latestPrice() == null || snap.prevClosePrice() == null) {
        return new MarketEnv("NEUTRAL", true);
      }
      // 无指数日K时：用现价相对昨收粗判，并默认视为可做多环境（避免误杀）
      boolean up = snap.latestPrice().compareTo(snap.prevClosePrice()) >= 0;
      // 尝试用个股池替代：若有 metrics 里指数 — 通常没有，保持宽松
      boolean above = true;
      String regime = up ? "BULL" : "NEUTRAL";
      return new MarketEnv(regime, above);
    } catch (Exception e) {
      return new MarketEnv("NEUTRAL", true);
    }
  }

  private boolean isSectorOk(String sectorTag, TradeStockBasic basic) {
    if (!props.getScreening().isRequireSectorTag()) {
      // 有标签或能匹配白名单即可，空标签给过但标记
      if (sectorTag != null && !sectorTag.isBlank()) return true;
    }
    String hay =
        ((sectorTag == null ? "" : sectorTag)
                + " "
                + (basic == null || basic.getSectorNames() == null ? "" : basic.getSectorNames()))
            .toLowerCase();
    for (String kw : props.getSectorWhitelist()) {
      if (hay.contains(kw.toLowerCase())) return true;
    }
    return !props.getScreening().isRequireSectorTag();
  }

  private boolean isValuationOk(TradeStockBasic basic) {
    if (basic == null) return true;
    if (basic.getPeTtm() != null
        && basic.getPeTtm().compareTo(props.getScreening().getPeSoftCap()) > 0) {
      return false;
    }
    return true;
  }

  private String inferSectorTag(TradeStockBasic basic) {
    if (basic == null || basic.getSectorNames() == null) return null;
    String names = basic.getSectorNames();
    for (String kw : props.getSectorWhitelist()) {
      if (names.contains(kw)) return kw;
    }
    String[] parts = names.split("[,，|/]");
    return parts.length > 0 ? parts[0].trim() : null;
  }

  private List<MoneyWatchDTO> enrichWatches(List<MoneyWatch> watches) {
    if (watches.isEmpty()) return List.of();
    List<String> codes = watches.stream().map(MoneyWatch::getStockCode).distinct().toList();
    Map<String, AStockDataQuoteService.QuoteSnapshot> quotes = quoteService.fetchQuotes(codes);
    Map<Long, MoneyStockPool> pools =
        poolRepository
            .findAllById(watches.stream().map(MoneyWatch::getPoolId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(MoneyStockPool::getId, p -> p));
    List<Long> ids = watches.stream().map(MoneyWatch::getId).toList();
    Map<Long, List<MoneySetup>> setups = new HashMap<>();
    for (String st : List.of("ACTIVE", "TRIGGERED", "EXPIRED")) {
      for (MoneySetup s : setupRepository.findByWatchIdInAndStatus(ids, st)) {
        setups.computeIfAbsent(s.getWatchId(), k -> new ArrayList<>()).add(s);
      }
    }
    Map<Long, MoneyPosition> posMap =
        positionRepository.findByStatusIn(Set.of("HOLDING", "PARTIAL_EXIT", "CLOSED")).stream()
            .filter(p -> ids.contains(p.getWatchId()))
            .collect(Collectors.toMap(MoneyPosition::getWatchId, p -> p, (a, b) -> a));

    Map<String, List<TradeStockDaily>> dailyMap = loadDaily(codes);
    List<MoneyWatchDTO> out = new ArrayList<>();
    for (MoneyWatch w : watches) {
      AStockDataQuoteService.QuoteSnapshot q =
          quotes.getOrDefault(normalize(w.getStockCode()), quotes.get(w.getStockCode()));
      MovingAverages mas =
          MovingAverageCalculator.fromDaily(dailyMap.getOrDefault(w.getStockCode(), List.of()));
      MoneyStockPool pool = pools.get(w.getPoolId());
      MoneyPosition pos = posMap.get(w.getId());
      BigDecimal latest = q == null ? (mas == null ? null : mas.latestClose()) : q.latestPrice();
      BigDecimal chg = null;
      if (q != null && q.latestPrice() != null && q.prevClosePrice() != null
          && q.prevClosePrice().compareTo(BigDecimal.ZERO) > 0) {
        chg =
            q.latestPrice()
                .subtract(q.prevClosePrice())
                .divide(q.prevClosePrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
      }
      out.add(
          MoneyWatchDTO.builder()
              .id(w.getId())
              .poolId(w.getPoolId())
              .stockCode(w.getStockCode())
              .stockName(w.getStockName())
              .status(w.getStatus())
              .sectorTag(w.getSectorTag())
              .screenPassed(integerTrue(w.getScreenPassed()))
              .screenDetail(parseJson(w.getScreenDetail()))
              .marketRegime(w.getMarketRegime())
              .indexAboveMa20(integerTrue(w.getIndexAboveMa20()))
              .buySignalType(w.getBuySignalType())
              .buySignalAt(w.getBuySignalAt())
              .buySignalPrice(w.getBuySignalPrice())
              .signalExpireAt(w.getSignalExpireAt())
              .invalidReason(w.getInvalidReason())
              .paperMode(pool != null && integerTrue(pool.getPaperMode()))
              .source(pool == null ? null : pool.getSource())
              .latestPrice(latest)
              .dailyChangePct(chg)
              .ma5(mas == null ? null : mas.ma5())
              .ma10(mas == null ? null : mas.ma10())
              .ma20(mas == null ? null : mas.ma20())
              .ma60(mas == null ? null : mas.ma60())
              .setups(
                  setups.getOrDefault(w.getId(), List.of()).stream()
                      .map(this::toSetupDto)
                      .toList())
              .position(pos == null ? null : toPositionDto(pos, latest))
              .updatedAt(w.getUpdatedAt())
              .build());
    }
    return out;
  }

  private MoneyPoolItemDTO toPoolDto(MoneyStockPool p, MoneyWatch w) {
    return MoneyPoolItemDTO.builder()
        .id(p.getId())
        .stockCode(p.getStockCode())
        .stockName(p.getStockName())
        .sectorTag(p.getSectorTag())
        .source(p.getSource())
        .status(p.getStatus())
        .paperMode(integerTrue(p.getPaperMode()))
        .memo(p.getMemo())
        .activeWatchId(w == null ? null : w.getId())
        .watchStatus(w == null ? null : w.getStatus())
        .createdAt(p.getCreatedAt())
        .updatedAt(p.getUpdatedAt())
        .build();
  }

  private MoneySetupDTO toSetupDto(MoneySetup s) {
    return MoneySetupDTO.builder()
        .id(s.getId())
        .setupType(s.getSetupType())
        .status(s.getStatus())
        .limitUpCount(s.getLimitUpCount())
        .platformLow(s.getPlatformLow())
        .platformOpen(s.getPlatformOpen())
        .limitUpVolume(s.getLimitUpVolume())
        .pullbackLow(s.getPullbackLow())
        .platformHigh(s.getPlatformHigh())
        .platformDays(s.getPlatformDays())
        .breakoutVolumeRatio(s.getBreakoutVolumeRatio())
        .triggerPrice(s.getTriggerPrice())
        .triggerAt(s.getTriggerAt())
        .build();
  }

  private MoneyPositionDTO toPositionDto(MoneyPosition p, BigDecimal latest) {
    BigDecimal entry = effectiveEntry(p);
    BigDecimal pnlPct = null;
    BigDecimal dd = null;
    if (latest != null && entry != null && entry.compareTo(BigDecimal.ZERO) > 0) {
      pnlPct =
          latest
              .subtract(entry)
              .divide(entry, 6, RoundingMode.HALF_UP)
              .multiply(BigDecimal.valueOf(100))
              .setScale(2, RoundingMode.HALF_UP);
    }
    if (latest != null && p.getPeakPrice() != null && p.getPeakPrice().compareTo(BigDecimal.ZERO) > 0) {
      dd =
          p.getPeakPrice()
              .subtract(latest)
              .divide(p.getPeakPrice(), 6, RoundingMode.HALF_UP)
              .multiply(BigDecimal.valueOf(100))
              .setScale(2, RoundingMode.HALF_UP);
    }
    return MoneyPositionDTO.builder()
        .id(p.getId())
        .watchId(p.getWatchId())
        .stockCode(p.getStockCode())
        .stockName(p.getStockName())
        .buyType(p.getBuyType())
        .entryPrice(p.getEntryPrice())
        .entryDate(p.getEntryDate())
        .entryShares(p.getEntryShares())
        .positionPct(p.getPositionPct())
        .peakPrice(p.getPeakPrice())
        .profitTier(p.getProfitTier())
        .stopPrimary(p.getStopPrimary())
        .stopSecondary(p.getStopSecondary())
        .trailingStop(p.getTrailingStop())
        .costStop(p.getCostStop())
        .addPositionDone(integerTrue(p.getAddPositionDone()))
        .latestPrice(latest)
        .unrealizedPnlPct(pnlPct)
        .peakDrawdownPct(dd)
        .status(p.getStatus())
        .closeReason(p.getCloseReason())
        .realizedPnl(p.getRealizedPnl())
        .realizedPnlPct(p.getRealizedPnlPct())
        .closedAt(p.getClosedAt())
        .build();
  }

  private MoneyEventDTO toEventDto(MoneyEvent e) {
    return MoneyEventDTO.builder()
        .id(e.getId())
        .watchId(e.getWatchId())
        .positionId(e.getPositionId())
        .stockCode(e.getStockCode())
        .stockName(e.getStockName())
        .eventType(e.getEventType())
        .severity(e.getSeverity())
        .title(e.getTitle())
        .content(e.getContent())
        .triggerPrice(e.getTriggerPrice())
        .pushed(integerTrue(e.getPushed()))
        .acknowledged(integerTrue(e.getAcknowledged()))
        .createdAt(e.getCreatedAt())
        .build();
  }

  private void saveEvent(
      MoneyWatch watch,
      MoneyPosition pos,
      String type,
      String severity,
      String title,
      String content,
      BigDecimal price,
      boolean push) {
    MoneyEvent e = new MoneyEvent();
    e.setWatchId(watch.getId());
    e.setPositionId(pos == null ? null : pos.getId());
    e.setPoolId(watch.getPoolId());
    e.setStockCode(watch.getStockCode());
    e.setStockName(watch.getStockName());
    e.setEventType(type);
    e.setSeverity(severity);
    e.setTitle(title);
    e.setContent(content);
    e.setTriggerPrice(price);
    if (push) {
      boolean ok = notificationService.sendServerChan(title, content);
      e.setPushed(ok ? 1 : 0);
    }
    eventRepository.save(e);
  }

  private void saveLeg(
      MoneyPosition pos,
      String type,
      BigDecimal price,
      Integer shares,
      LocalDateTime date,
      String source,
      Long eventId,
      String memo) {
    MoneyTradeLeg leg = new MoneyTradeLeg();
    leg.setPositionId(pos.getId());
    leg.setWatchId(pos.getWatchId());
    leg.setStockCode(pos.getStockCode());
    leg.setLegType(type);
    leg.setPrice(price);
    leg.setShares(shares);
    if (shares != null) {
      leg.setAmount(price.multiply(BigDecimal.valueOf(shares)));
    }
    leg.setTradeDate(date);
    leg.setSource(source);
    leg.setLinkedEventId(eventId);
    leg.setMemo(memo);
    tradeLegRepository.save(leg);
  }

  private BigDecimal effectiveEntry(MoneyPosition pos) {
    if (integerTrue(pos.getAddPositionDone())
        && pos.getAddEntryPrice() != null
        && pos.getEntryShares() != null
        && pos.getAddShares() != null
        && pos.getEntryShares() + pos.getAddShares() > 0) {
      return pos.getEntryPrice()
          .multiply(BigDecimal.valueOf(pos.getEntryShares()))
          .add(pos.getAddEntryPrice().multiply(BigDecimal.valueOf(pos.getAddShares())))
          .divide(
              BigDecimal.valueOf(pos.getEntryShares() + pos.getAddShares()),
              2,
              RoundingMode.HALF_UP);
    }
    return pos.getEntryPrice();
  }

  private int totalShares(MoneyPosition pos) {
    int a = pos.getEntryShares() == null ? 0 : pos.getEntryShares();
    int b = pos.getAddShares() == null ? 0 : pos.getAddShares();
    return a + b;
  }

  private boolean isTradingTime(LocalDateTime now) {
    LocalTime t = now.toLocalTime();
    return (t.isAfter(LocalTime.of(9, 29)) && t.isBefore(LocalTime.of(11, 31)))
        || (t.isAfter(LocalTime.of(12, 59)) && t.isBefore(LocalTime.of(15, 1)));
  }

  private boolean isWatching(String status) {
    return "SCREENING".equals(status)
        || "WATCH_PULLBACK".equals(status)
        || "WATCH_BREAKOUT".equals(status);
  }

  private boolean integerTrue(Integer v) {
    return v != null && v == 1;
  }

  private BigDecimal max(BigDecimal a, BigDecimal b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.max(b);
  }

  private String normalize(String code) {
    return code == null ? null : code.trim().toUpperCase();
  }

  private String toJson(Object o) {
    try {
      return o == null ? null : objectMapper.writeValueAsString(o);
    } catch (Exception e) {
      return null;
    }
  }

  private Map<String, Object> parseJson(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private record MarketEnv(String regime, boolean aboveMa20) {}
}
