package com.quant.service.swing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.config.SwingTradingProperties;
import com.quant.dto.swing.SwingFillConfirmRequest;
import com.quant.dto.swing.SwingSignalDTO;
import com.quant.dto.swing.SwingStatsDTO;
import com.quant.dto.swing.SwingWatchDTO;
import com.quant.dto.swing.SwingWatchPatchRequest;
import com.quant.dto.swing.SwingWatchRequest;
import com.quant.entity.SwingFill;
import com.quant.entity.SwingPosition;
import com.quant.entity.SwingSetup;
import com.quant.entity.SwingSignal;
import com.quant.entity.SwingWatchlist;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.SwingFillRepository;
import com.quant.repository.SwingPositionRepository;
import com.quant.repository.SwingSetupRepository;
import com.quant.repository.SwingSignalRepository;
import com.quant.repository.SwingWatchlistRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SwingWatchService {

  private static final Set<String> OPEN_POS =
      Set.of(SwingConstants.POS_OPEN, SwingConstants.POS_PARTIAL);
  private static final Set<String> HOLDING =
      Set.of(SwingConstants.WATCH_HOLDING, SwingConstants.WATCH_HOLDING_PARTIAL);

  private final SwingTradingProperties props;
  private final SwingWatchlistRepository watchRepository;
  private final SwingSetupRepository setupRepository;
  private final SwingPositionRepository positionRepository;
  private final SwingFillRepository fillRepository;
  private final SwingSignalRepository signalRepository;
  private final TradeStockBasicRepository basicRepository;
  private final SwingIndicatorService indicatorService;
  private final SwingScanService scanService;
  private final AStockDataQuoteService quoteService;

  @Transactional
  public SwingWatchDTO add(Long userId, SwingWatchRequest req) {
    String code = SwingCodeUtils.normalize(req.getStockCode());
    if (code.isBlank()) {
      throw new IllegalArgumentException("股票代码无效");
    }
    watchRepository
        .findByUserIdAndStockCode(userId, code)
        .ifPresent(
            w -> {
              throw new IllegalArgumentException("该股票已在监控池中");
            });

    long openCount = positionRepository.countByUserIdAndStatusIn(userId, OPEN_POS);
    if (openCount >= props.getMaxOpenPositions()) {
      throw new IllegalArgumentException("同时持仓不得超过 " + props.getMaxOpenPositions() + " 只");
    }

    String name =
        basicRepository
            .findByStockCode(code)
            .map(TradeStockBasic::getStockName)
            .orElseGet(
                () ->
                    basicRepository
                        .findByStockCode(SwingCodeUtils.bareCode(code))
                        .map(TradeStockBasic::getStockName)
                        .orElse(code));

    SwingWatchlist watch = new SwingWatchlist();
    watch.setUserId(userId);
    watch.setStockCode(code);
    watch.setStockName(name);
    watch.setSectorTag(req.getSectorTag().trim());
    watch.setThesis(req.getThesis());
    watch.setHardFilterOk(req.getHardFilterOk() == null || req.getHardFilterOk());
    watch.setQuietPeriod(Boolean.TRUE.equals(req.getQuietPeriod()));
    watch.setPreferredSetup(
        req.getPreferredSetup() == null ? "BOTH" : req.getPreferredSetup().toUpperCase());
    watch.setTradeMode(SwingConstants.MODE_HYBRID);
    watch.setStatus(SwingConstants.WATCH_WATCHING);
    watch.setAccountEquity(
        req.getAccountEquity() != null ? req.getAccountEquity() : props.getDefaultAccountEquity());
    watch.setMaxPositionPct(
        req.getMaxPositionPct() != null
            ? req.getMaxPositionPct()
            : props.getMaxSinglePositionPct());
    watch.setServerchanSendKey(req.getServerchanSendKey());
    watch.setNote(req.getNote());
    watch = watchRepository.save(watch);

    // 立即扫描一次（忽略交易时段）
    scanService.scanOne(watch, quoteService.fetchQuotes(List.of(code)).get(code), false);
    return toDto(watchRepository.findById(watch.getId()).orElse(watch), null);
  }

  @Transactional(readOnly = true)
  public List<SwingWatchDTO> list(Long userId) {
    List<SwingWatchlist> watches = watchRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    List<String> codes = watches.stream().map(SwingWatchlist::getStockCode).distinct().toList();
    Map<String, AStockDataQuoteService.QuoteSnapshot> quotes =
        codes.isEmpty() ? Map.of() : quoteService.fetchQuotes(codes);
    return watches.stream().map(w -> toDto(w, quotes.get(w.getStockCode()))).toList();
  }

  @Transactional(readOnly = true)
  public SwingWatchDTO get(Long userId, Long id) {
    SwingWatchlist watch =
        watchRepository
            .findByIdAndUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("监控不存在"));
    var quote = quoteService.fetchQuotes(List.of(watch.getStockCode())).get(watch.getStockCode());
    SwingWatchDTO dto = toDto(watch, quote);
    dto.setRecentSignals(
        signalRepository.findTop20ByWatchIdOrderByCreatedAtDesc(id).stream()
            .map(s -> toSignalDto(s, watch))
            .toList());
    return dto;
  }

  @Transactional
  public SwingWatchDTO patch(Long userId, Long id, SwingWatchPatchRequest req) {
    SwingWatchlist watch =
        watchRepository
            .findByIdAndUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("监控不存在"));
    if (req.getSectorTag() != null) {
      watch.setSectorTag(req.getSectorTag());
    }
    if (req.getThesis() != null) {
      watch.setThesis(req.getThesis());
    }
    if (req.getHardFilterOk() != null) {
      watch.setHardFilterOk(req.getHardFilterOk());
    }
    if (req.getQuietPeriod() != null) {
      watch.setQuietPeriod(req.getQuietPeriod());
    }
    if (req.getPreferredSetup() != null) {
      watch.setPreferredSetup(req.getPreferredSetup().toUpperCase());
    }
    if (req.getStatus() != null) {
      watch.setStatus(req.getStatus());
    }
    if (req.getAccountEquity() != null) {
      watch.setAccountEquity(req.getAccountEquity());
    }
    if (req.getMaxPositionPct() != null) {
      watch.setMaxPositionPct(req.getMaxPositionPct());
    }
    if (req.getServerchanSendKey() != null) {
      watch.setServerchanSendKey(req.getServerchanSendKey());
    }
    if (req.getNote() != null) {
      watch.setNote(req.getNote());
    }
    return toDto(watchRepository.save(watch), null);
  }

  @Transactional
  public void delete(Long userId, Long id) {
    SwingWatchlist watch =
        watchRepository
            .findByIdAndUserId(id, userId)
            .orElseThrow(() -> new IllegalArgumentException("监控不存在"));
    if (HOLDING.contains(watch.getStatus())) {
      throw new IllegalArgumentException("持仓中不可删除，请先平仓或将状态改为 CLOSED");
    }
    watchRepository.delete(watch);
  }

  @Transactional(readOnly = true)
  public List<SwingSignalDTO> listSignals(Long userId) {
    Map<Long, SwingWatchlist> watchMap =
        watchRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
            .collect(Collectors.toMap(SwingWatchlist::getId, w -> w, (a, b) -> a));
    return signalRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
        .map(s -> toSignalDto(s, watchMap.get(s.getWatchId())))
        .toList();
  }

  @Transactional
  public SwingSignalDTO ackSignal(Long userId, Long signalId) {
    SwingSignal signal =
        signalRepository
            .findByIdAndUserId(signalId, userId)
            .orElseThrow(() -> new IllegalArgumentException("信号不存在"));
    signal.setStatus(SwingConstants.SIGNAL_ACKED);
    signal.setAckedAt(java.time.LocalDateTime.now());
    signalRepository.save(signal);
    SwingWatchlist watch = watchRepository.findById(signal.getWatchId()).orElse(null);
    return toSignalDto(signal, watch);
  }

  @Transactional
  public SwingWatchDTO confirmFill(Long userId, Long signalId, SwingFillConfirmRequest req) {
    SwingSignal signal =
        signalRepository
            .findByIdAndUserId(signalId, userId)
            .orElseThrow(() -> new IllegalArgumentException("信号不存在"));
    SwingWatchlist watch =
        watchRepository
            .findByIdAndUserId(signal.getWatchId(), userId)
            .orElseThrow(() -> new IllegalArgumentException("监控不存在"));

    String side =
        req.getSide() != null
            ? req.getSide().toUpperCase()
            : ("BUY".equalsIgnoreCase(signal.getSuggestAction())
                ? SwingConstants.SIDE_BUY
                : SwingConstants.SIDE_SELL);

    var openOpt =
        positionRepository.findFirstByWatchIdAndStatusInOrderByEntryTimeDesc(
            watch.getId(), OPEN_POS);

    if (SwingConstants.SIDE_BUY.equals(side)) {
      if (openOpt.isEmpty()) {
        // 人工补录开仓（若系统尚未自动开）
        SwingPosition pos = new SwingPosition();
        pos.setWatchId(watch.getId());
        pos.setUserId(userId);
        pos.setStockCode(watch.getStockCode());
        pos.setSetupId(signal.getSetupId());
        pos.setSetupType(
            SwingConstants.SIG_ENTRY_BREAKOUT.equals(signal.getSignalType())
                ? SwingConstants.SETUP_BREAKOUT
                : SwingConstants.SETUP_PULLBACK);
        pos.setStatus(SwingConstants.POS_OPEN);
        pos.setEntryTime(java.time.LocalDateTime.now());
        pos.setAvgCost(req.getPrice());
        pos.setShares(req.getShares());
        pos.setInitialShares(req.getShares());
        pos.setPeakPrice(req.getPrice());
        BigDecimal stop =
            signal.getSuggestStop() != null
                ? signal.getSuggestStop()
                : req.getPrice().multiply(BigDecimal.valueOf(0.92));
        pos.setHardStopPrice(stop);
        pos.setStopPrice(stop);
        pos.setSoftStopPct(props.getPullbackSoftStopPct());
        pos.setProfitTier("T0");
        pos = positionRepository.save(pos);
        saveUserFill(pos, watch.getId(), side, "ENTRY", req, signal.getId());
        watch.setStatus(SwingConstants.WATCH_HOLDING);
      } else {
        SwingPosition pos = openOpt.get();
        BigDecimal old = pos.getAvgCost().multiply(BigDecimal.valueOf(pos.getShares()));
        BigDecimal add = req.getPrice().multiply(BigDecimal.valueOf(req.getShares()));
        int ns = pos.getShares() + req.getShares();
        pos.setAvgCost(old.add(add).divide(BigDecimal.valueOf(ns), 2, RoundingMode.HALF_UP));
        pos.setShares(ns);
        positionRepository.save(pos);
        saveUserFill(pos, watch.getId(), side, "MANUAL", req, signal.getId());
      }
    } else {
      SwingPosition pos = openOpt.orElseThrow(() -> new IllegalArgumentException("无持仓可卖"));
      int sell = Math.min(req.getShares(), pos.getShares());
      BigDecimal pnl = req.getPrice().subtract(pos.getAvgCost()).multiply(BigDecimal.valueOf(sell));
      pos.setRealizedPnl(
          (pos.getRealizedPnl() == null ? BigDecimal.ZERO : pos.getRealizedPnl()).add(pnl));
      pos.setShares(pos.getShares() - sell);
      if (pos.getShares() <= 0) {
        pos.setStatus(SwingConstants.POS_CLOSED);
        pos.setExitTime(java.time.LocalDateTime.now());
        pos.setExitReason("MANUAL");
        watch.setStatus(SwingConstants.WATCH_CLOSED);
      } else {
        pos.setStatus(SwingConstants.POS_PARTIAL);
        pos.setPartialExits(pos.getPartialExits() + 1);
        watch.setStatus(SwingConstants.WATCH_HOLDING_PARTIAL);
      }
      positionRepository.save(pos);
      saveUserFill(pos, watch.getId(), side, "MANUAL", req, signal.getId());
    }

    signal.setAckedAt(java.time.LocalDateTime.now());
    signal.setExecutedAt(java.time.LocalDateTime.now());
    signal.setStatus(SwingConstants.SIGNAL_EXECUTED);
    signalRepository.save(signal);
    watchRepository.save(watch);
    return get(userId, watch.getId());
  }

  @Transactional(readOnly = true)
  public SwingStatsDTO stats(Long userId) {
    List<SwingPosition> all = positionRepository.findByUserIdOrderByEntryTimeDesc(userId);
    List<SwingPosition> closed =
        all.stream().filter(p -> SwingConstants.POS_CLOSED.equals(p.getStatus())).toList();
    int wins = 0;
    int losses = 0;
    BigDecimal totalPnl = BigDecimal.ZERO;
    BigDecimal winSum = BigDecimal.ZERO;
    BigDecimal lossSum = BigDecimal.ZERO;
    Map<String, Integer> exitReasons = new java.util.LinkedHashMap<>();
    Map<String, Integer> setupTypes = new java.util.LinkedHashMap<>();
    for (SwingPosition p : closed) {
      BigDecimal pnl = p.getRealizedPnl() == null ? BigDecimal.ZERO : p.getRealizedPnl();
      totalPnl = totalPnl.add(pnl);
      if (pnl.compareTo(BigDecimal.ZERO) > 0) {
        wins++;
        winSum = winSum.add(pnl);
      } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
        losses++;
        lossSum = lossSum.add(pnl.abs());
      }
      exitReasons.merge(p.getExitReason() == null ? "UNKNOWN" : p.getExitReason(), 1, Integer::sum);
      setupTypes.merge(p.getSetupType() == null ? "UNKNOWN" : p.getSetupType(), 1, Integer::sum);
    }
    int total = closed.size();
    BigDecimal winRate =
        total == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    BigDecimal avgWin =
        wins == 0
            ? BigDecimal.ZERO
            : winSum.divide(BigDecimal.valueOf(wins), 2, RoundingMode.HALF_UP);
    BigDecimal avgLoss =
        losses == 0
            ? BigDecimal.ZERO
            : lossSum.divide(BigDecimal.valueOf(losses), 2, RoundingMode.HALF_UP);
    BigDecimal pf =
        lossSum.compareTo(BigDecimal.ZERO) == 0
            ? (winSum.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(99) : BigDecimal.ZERO)
            : winSum.divide(lossSum, 2, RoundingMode.HALF_UP);

    long open = positionRepository.countByUserIdAndStatusIn(userId, OPEN_POS);
    long watching =
        watchRepository.countByUserIdAndStatusIn(
            userId,
            List.of(
                SwingConstants.WATCH_WATCHING,
                SwingConstants.WATCH_SETUP_PULLBACK,
                SwingConstants.WATCH_SETUP_BREAKOUT,
                SwingConstants.WATCH_ENTRY_SIGNALED));

    return SwingStatsDTO.builder()
        .totalClosed(total)
        .wins(wins)
        .losses(losses)
        .winRate(winRate)
        .totalPnl(totalPnl)
        .avgWin(avgWin)
        .avgLoss(avgLoss)
        .profitFactor(pf)
        .exitReasonCounts(exitReasons)
        .setupTypeCounts(setupTypes)
        .openPositions((int) open)
        .watchingCount((int) watching)
        .build();
  }

  private void saveUserFill(
      SwingPosition pos,
      Long watchId,
      String side,
      String reason,
      SwingFillConfirmRequest req,
      Long signalId) {
    SwingFill fill = new SwingFill();
    fill.setPositionId(pos.getId());
    fill.setWatchId(watchId);
    fill.setSide(side);
    fill.setReason(reason);
    fill.setPrice(req.getPrice());
    fill.setShares(req.getShares());
    fill.setAmount(req.getPrice().multiply(BigDecimal.valueOf(req.getShares())));
    fill.setFillTime(java.time.LocalDateTime.now());
    fill.setSource(SwingConstants.SRC_USER);
    fill.setSignalId(signalId);
    fill.setNote(req.getNote());
    fillRepository.save(fill);
  }

  private SwingWatchDTO toDto(SwingWatchlist watch, AStockDataQuoteService.QuoteSnapshot quote) {
    var ma = indicatorService.compute(indicatorService.loadAsc(watch.getStockCode(), 80));
    BigDecimal latest =
        quote != null && quote.latestPrice() != null
            ? quote.latestPrice()
            : (ma == null ? null : ma.latestClose());

    var activeSetups =
        setupRepository.findByWatchIdAndStatusInOrderByDetectedAtDesc(
            watch.getId(), List.of(SwingConstants.SETUP_ACTIVE));
    SwingSetup active = activeSetups.isEmpty() ? null : activeSetups.get(0);

    var posOpt =
        positionRepository.findFirstByWatchIdAndStatusInOrderByEntryTimeDesc(
            watch.getId(), OPEN_POS);

    BigDecimal unrealized = null;
    BigDecimal unrealizedPct = null;
    if (posOpt.isPresent() && latest != null) {
      SwingPosition p = posOpt.get();
      unrealized = latest.subtract(p.getAvgCost()).multiply(BigDecimal.valueOf(p.getShares()));
      unrealizedPct =
          latest
              .subtract(p.getAvgCost())
              .divide(p.getAvgCost(), 4, RoundingMode.HALF_UP)
              .multiply(BigDecimal.valueOf(100))
              .setScale(2, RoundingMode.HALF_UP);
    }

    boolean preOk =
        ma != null
            && ma.bullishAligned()
            && ma.aboveMa20()
            && ma.ma20Rising()
            && ma.volRatio() != null
            && ma.volRatio().compareTo(props.getVolRatioMin()) >= 0;

    return SwingWatchDTO.builder()
        .id(watch.getId())
        .stockCode(watch.getStockCode())
        .stockName(watch.getStockName())
        .sectorTag(watch.getSectorTag())
        .thesis(watch.getThesis())
        .hardFilterOk(watch.getHardFilterOk())
        .quietPeriod(watch.getQuietPeriod())
        .preferredSetup(watch.getPreferredSetup())
        .tradeMode(watch.getTradeMode())
        .status(watch.getStatus())
        .accountEquity(watch.getAccountEquity())
        .maxPositionPct(watch.getMaxPositionPct())
        .latestPrice(latest)
        .ma20(ma == null ? null : ma.ma20())
        .preconditionsOk(preOk)
        .activeSetupType(active == null ? null : active.getSetupType())
        .pullbackZoneHigh(active == null ? null : active.getPullbackZoneHigh())
        .pullbackZoneLow(active == null ? null : active.getPullbackZoneLow())
        .platformHigh(active == null ? null : active.getPlatformHigh())
        .avgCost(posOpt.map(SwingPosition::getAvgCost).orElse(null))
        .shares(posOpt.map(SwingPosition::getShares).orElse(null))
        .peakPrice(posOpt.map(SwingPosition::getPeakPrice).orElse(null))
        .stopPrice(posOpt.map(SwingPosition::getStopPrice).orElse(null))
        .unrealizedPnl(unrealized)
        .unrealizedPnlPct(unrealizedPct)
        .profitTier(posOpt.map(SwingPosition::getProfitTier).orElse(null))
        .lastScanAt(watch.getLastScanAt())
        .lastSignalAt(watch.getLastSignalAt())
        .createdAt(watch.getCreatedAt())
        .build();
  }

  private SwingSignalDTO toSignalDto(SwingSignal s, SwingWatchlist watch) {
    return SwingSignalDTO.builder()
        .id(s.getId())
        .watchId(s.getWatchId())
        .stockCode(watch == null ? null : watch.getStockCode())
        .stockName(watch == null ? null : watch.getStockName())
        .signalType(s.getSignalType())
        .level(s.getLevel())
        .title(s.getTitle())
        .content(s.getContent())
        .triggerPrice(s.getTriggerPrice())
        .suggestAction(s.getSuggestAction())
        .suggestShares(s.getSuggestShares())
        .suggestStop(s.getSuggestStop())
        .status(s.getStatus())
        .notifiedAt(s.getNotifiedAt())
        .executedAt(s.getExecutedAt())
        .createdAt(s.getCreatedAt())
        .build();
  }
}
