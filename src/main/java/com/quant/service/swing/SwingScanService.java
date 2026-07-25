package com.quant.service.swing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.config.SwingTradingProperties;
import com.quant.entity.SwingEvent;
import com.quant.entity.SwingFill;
import com.quant.entity.SwingPosition;
import com.quant.entity.SwingSetup;
import com.quant.entity.SwingSignal;
import com.quant.entity.SwingWatchlist;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.SwingEventRepository;
import com.quant.repository.SwingFillRepository;
import com.quant.repository.SwingPositionRepository;
import com.quant.repository.SwingSetupRepository;
import com.quant.repository.SwingSignalRepository;
import com.quant.repository.SwingWatchlistRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;
import com.quant.service.potential.PotentialPoolSupport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SwingScanService {

  private static final Set<String> ACTIVE_WATCH =
      Set.of(
          SwingConstants.WATCH_WATCHING,
          SwingConstants.WATCH_SETUP_PULLBACK,
          SwingConstants.WATCH_SETUP_BREAKOUT,
          SwingConstants.WATCH_ENTRY_SIGNALED,
          SwingConstants.WATCH_HOLDING,
          SwingConstants.WATCH_HOLDING_PARTIAL);

  private static final Set<String> OPEN_POS =
      Set.of(SwingConstants.POS_OPEN, SwingConstants.POS_PARTIAL);

  private final SwingTradingProperties props;
  private final SwingWatchlistRepository watchRepository;
  private final SwingSetupRepository setupRepository;
  private final SwingPositionRepository positionRepository;
  private final SwingFillRepository fillRepository;
  private final SwingSignalRepository signalRepository;
  private final SwingEventRepository eventRepository;
  private final SwingIndicatorService indicatorService;
  private final SwingSignalNotifier notifier;
  private final SwingPositionSizer sizer;
  private final AStockDataQuoteService quoteService;

  public record ScanResult(int scanned, int setups, int signals, int fills) {}

  @Transactional
  public ScanResult scanAll(boolean forceIgnoreTradingTime) {
    if (!props.isEnabled()) {
      return new ScanResult(0, 0, 0, 0);
    }
    if (!forceIgnoreTradingTime
        && props.isRequireTradingTime()
        && !PotentialPoolSupport.isTradingTime()) {
      return new ScanResult(0, 0, 0, 0);
    }
    List<SwingWatchlist> watches = watchRepository.findByStatusIn(ACTIVE_WATCH);
    if (watches.isEmpty()) {
      return new ScanResult(0, 0, 0, 0);
    }
    List<String> codes = watches.stream().map(SwingWatchlist::getStockCode).distinct().toList();
    Map<String, AStockDataQuoteService.QuoteSnapshot> quotes = quoteService.fetchQuotes(codes);

    int setups = 0;
    int signals = 0;
    int fills = 0;
    for (SwingWatchlist watch : watches) {
      try {
        var r = scanOne(watch, quotes.get(watch.getStockCode()), false);
        setups += r.setups;
        signals += r.signals;
        fills += r.fills;
      } catch (Exception e) {
        log.warn("swing scan failed watchId={}: {}", watch.getId(), e.getMessage());
      }
    }
    return new ScanResult(watches.size(), setups, signals, fills);
  }

  @Transactional
  public ScanResult scanEod() {
    if (!props.isEnabled()) {
      return new ScanResult(0, 0, 0, 0);
    }
    List<SwingWatchlist> watches = watchRepository.findByStatusIn(ACTIVE_WATCH);
    int setups = 0;
    int signals = 0;
    int fills = 0;
    for (SwingWatchlist watch : watches) {
      try {
        var r = scanOne(watch, null, true);
        setups += r.setups;
        signals += r.signals;
        fills += r.fills;
      } catch (Exception e) {
        log.warn("swing eod scan failed watchId={}: {}", watch.getId(), e.getMessage());
      }
    }
    return new ScanResult(watches.size(), setups, signals, fills);
  }

  @Transactional
  public ScanResult scanOne(
      SwingWatchlist watch, AStockDataQuoteService.QuoteSnapshot quote, boolean eod) {
    int setups = 0;
    int signals = 0;
    int fills = 0;

    List<TradeStockDaily> asc = indicatorService.loadAsc(watch.getStockCode(), 80);
    SwingIndicatorService.MaSnapshot ma = indicatorService.compute(asc);
    BigDecimal price = resolvePrice(quote, ma);

    watch.setLastScanAt(LocalDateTime.now());

    if (Boolean.TRUE.equals(watch.getQuietPeriod())
        || !Boolean.TRUE.equals(watch.getHardFilterOk())) {
      if (!SwingConstants.WATCH_HOLDING.equals(watch.getStatus())
          && !SwingConstants.WATCH_HOLDING_PARTIAL.equals(watch.getStatus())) {
        transition(watch, SwingConstants.WATCH_FILTERED, "quiet_or_filter", null);
        watchRepository.save(watch);
        return new ScanResult(1, 0, 0, 0);
      }
    }

    var openPos =
        positionRepository.findFirstByWatchIdAndStatusInOrderByEntryTimeDesc(
            watch.getId(), OPEN_POS);

    if (openPos.isPresent()) {
      var r = managePosition(watch, openPos.get(), ma, asc, price, eod);
      signals += r.signals;
      fills += r.fills;
      watchRepository.save(watch);
      return new ScanResult(1, setups, signals, fills);
    }

    boolean preOk = preconditionsOk(ma);
    if (!preOk) {
      if (!SwingConstants.WATCH_WATCHING.equals(watch.getStatus())
          && !SwingConstants.WATCH_ENTRY_SIGNALED.equals(watch.getStatus())) {
        transition(watch, SwingConstants.WATCH_WATCHING, "precondition_fail", null);
      }
      expireActiveSetups(watch.getId(), "前置条件失效");
      watchRepository.save(watch);
      return new ScanResult(1, 0, 0, 0);
    }

    String preferred =
        watch.getPreferredSetup() == null ? "BOTH" : watch.getPreferredSetup().toUpperCase();

    if ("PULLBACK".equals(preferred) || "BOTH".equals(preferred)) {
      var created = ensurePullbackSetup(watch, asc, ma);
      if (created != null) {
        setups++;
        signals += notifySetup(watch, created);
      }
    }
    if ("BREAKOUT".equals(preferred) || "BOTH".equals(preferred)) {
      var created = ensureBreakoutSetup(watch, asc, ma);
      if (created != null) {
        setups++;
        signals += notifySetup(watch, created);
      }
    }

    var pullback =
        setupRepository.findFirstByWatchIdAndSetupTypeAndStatusOrderByDetectedAtDesc(
            watch.getId(), SwingConstants.SETUP_PULLBACK, SwingConstants.SETUP_ACTIVE);
    if (pullback.isPresent() && price != null) {
      var r = tryTriggerPullback(watch, pullback.get(), asc, ma, price);
      signals += r.signals;
      fills += r.fills;
    }

    var breakout =
        setupRepository.findFirstByWatchIdAndSetupTypeAndStatusOrderByDetectedAtDesc(
            watch.getId(), SwingConstants.SETUP_BREAKOUT, SwingConstants.SETUP_ACTIVE);
    if (breakout.isPresent() && price != null) {
      var r = tryTriggerBreakout(watch, breakout.get(), asc, ma, price, eod);
      signals += r.signals;
      fills += r.fills;
    }

    expireStaleSetups(watch);
    watchRepository.save(watch);
    return new ScanResult(1, setups, signals, fills);
  }

  private boolean preconditionsOk(SwingIndicatorService.MaSnapshot ma) {
    if (ma == null) {
      return false;
    }
    if (!ma.bullishAligned() || !ma.aboveMa20() || !ma.ma20Rising()) {
      return false;
    }
    return ma.volRatio() != null && ma.volRatio().compareTo(props.getVolRatioMin()) >= 0;
  }

  private SwingSetup ensurePullbackSetup(
      SwingWatchlist watch, List<TradeStockDaily> asc, SwingIndicatorService.MaSnapshot ma) {
    var existing =
        setupRepository.findFirstByWatchIdAndSetupTypeAndStatusOrderByDetectedAtDesc(
            watch.getId(), SwingConstants.SETUP_PULLBACK, SwingConstants.SETUP_ACTIVE);
    if (existing.isPresent()) {
      return null;
    }
    // 从近到远找最近 1~2 根涨停
    TradeStockDaily limitBar = null;
    int from = Math.max(0, asc.size() - props.getPullbackExpireDays() - 2);
    for (int i = asc.size() - 1; i > from; i--) {
      TradeStockDaily cur = asc.get(i);
      TradeStockDaily prev = asc.get(i - 1);
      if (indicatorService.isLimitUp(cur, prev, watch.getStockCode(), watch.getStockName())) {
        limitBar = cur;
        break;
      }
    }
    if (limitBar == null
        || limitBar.getOpenPrice() == null
        || limitBar.getLowPrice() == null
        || limitBar.getVolume() == null) {
      return null;
    }
    SwingSetup setup = baseSetup(watch, SwingConstants.SETUP_PULLBACK, ma);
    setup.setLimitUpDate(limitBar.getTradeDate());
    setup.setLimitUpOpen(limitBar.getOpenPrice());
    setup.setLimitUpLow(limitBar.getLowPrice());
    setup.setLimitUpClose(limitBar.getClosePrice());
    setup.setLimitUpVolume(limitBar.getVolume());
    // 锚点：开盘价为主（zone high），最低价为极限（zone low）
    setup.setPullbackZoneHigh(limitBar.getOpenPrice());
    setup.setPullbackZoneLow(limitBar.getLowPrice());
    setup.setExpireDate(limitBar.getTradeDate().plusDays(props.getPullbackExpireDays() + 6));
    setup = setupRepository.save(setup);
    transition(watch, SwingConstants.WATCH_SETUP_PULLBACK, "pullback_setup", setup.getId());
    return setup;
  }

  private SwingSetup ensureBreakoutSetup(
      SwingWatchlist watch, List<TradeStockDaily> asc, SwingIndicatorService.MaSnapshot ma) {
    var existing =
        setupRepository.findFirstByWatchIdAndSetupTypeAndStatusOrderByDetectedAtDesc(
            watch.getId(), SwingConstants.SETUP_BREAKOUT, SwingConstants.SETUP_ACTIVE);
    if (existing.isPresent()) {
      return null;
    }
    if (asc.size() < props.getPlatformMaxDays() + 5) {
      return null;
    }
    int window = props.getPlatformMaxDays();
    List<TradeStockDaily> platform = asc.subList(asc.size() - window, asc.size());
    // 要求平台内都在 MA20 上方，且高点波动收窄
    BigDecimal high = null;
    BigDecimal low = null;
    for (TradeStockDaily d : platform) {
      if (d.getHighPrice() == null || d.getLowPrice() == null || d.getClosePrice() == null) {
        return null;
      }
      if (ma.ma20() != null && d.getClosePrice().compareTo(ma.ma20()) < 0) {
        return null;
      }
      high = high == null || d.getHighPrice().compareTo(high) > 0 ? d.getHighPrice() : high;
      low = low == null || d.getLowPrice().compareTo(low) < 0 ? d.getLowPrice() : low;
    }
    if (high == null || low == null || high.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    BigDecimal rangePct = high.subtract(low).divide(high, 4, RoundingMode.HALF_UP);
    // 震荡收窄：区间 < 12%
    if (rangePct.compareTo(BigDecimal.valueOf(0.12)) > 0) {
      return null;
    }
    // 至少 platformMinDays 根
    int days = platform.size();
    if (days < props.getPlatformMinDays()) {
      return null;
    }
    SwingSetup setup = baseSetup(watch, SwingConstants.SETUP_BREAKOUT, ma);
    setup.setPlatformHigh(high);
    setup.setPlatformStart(platform.get(0).getTradeDate());
    setup.setPlatformEnd(platform.get(platform.size() - 1).getTradeDate());
    setup.setPlatformDays(days);
    setup.setExpireDate(LocalDate.now().plusDays(15));
    setup = setupRepository.save(setup);
    if (!SwingConstants.WATCH_SETUP_PULLBACK.equals(watch.getStatus())) {
      transition(watch, SwingConstants.WATCH_SETUP_BREAKOUT, "breakout_setup", setup.getId());
    }
    return setup;
  }

  private SwingSetup baseSetup(
      SwingWatchlist watch, String type, SwingIndicatorService.MaSnapshot ma) {
    SwingSetup setup = new SwingSetup();
    setup.setWatchId(watch.getId());
    setup.setSetupType(type);
    setup.setStatus(SwingConstants.SETUP_ACTIVE);
    setup.setMa5(ma.ma5());
    setup.setMa10(ma.ma10());
    setup.setMa20(ma.ma20());
    setup.setMa60(ma.ma60());
    setup.setMa20Slope(ma.ma20Slope());
    setup.setVolMa20(ma.volMa20());
    setup.setVolMa60(ma.volMa60());
    setup.setVolRatio(ma.volRatio());
    setup.setDetectedAt(LocalDateTime.now());
    return setup;
  }

  private int notifySetup(SwingWatchlist watch, SwingSetup setup) {
    String title =
        String.format(
            "[趋势波段] %s 识别%s结构",
            display(watch),
            SwingConstants.SETUP_PULLBACK.equals(setup.getSetupType()) ? "回踩" : "突破");
    String content =
        SwingConstants.SETUP_PULLBACK.equals(setup.getSetupType())
            ? String.format(
                "涨停日 %s\n回踩区 %.2f ~ %.2f\n涨停量 %d\n请等待缩量回踩后收阳/站上5日线。",
                setup.getLimitUpDate(),
                setup.getPullbackZoneLow(),
                setup.getPullbackZoneHigh(),
                setup.getLimitUpVolume())
            : String.format(
                "平台高点 %.2f\n整理 %d 日（%s ~ %s）\n等待放量突破尾盘确认或次日回踩不破。",
                setup.getPlatformHigh(),
                setup.getPlatformDays(),
                setup.getPlatformStart(),
                setup.getPlatformEnd());
    var emit =
        notifier.emit(
            watch,
            SwingConstants.SIG_SETUP_DETECTED + "_" + setup.getSetupType(),
            SwingConstants.LEVEL_INFO,
            title,
            content,
            null,
            "HOLD",
            null,
            null,
            setup.getId(),
            null,
            true);
    return emit.created() ? 1 : 0;
  }

  private ScanResult tryTriggerPullback(
      SwingWatchlist watch,
      SwingSetup setup,
      List<TradeStockDaily> asc,
      SwingIndicatorService.MaSnapshot ma,
      BigDecimal price) {
    int signals = 0;
    int fills = 0;
    if (setup.getPullbackZoneLow() == null || setup.getPullbackZoneHigh() == null) {
      return new ScanResult(0, 0, 0, 0);
    }
    // 是否进入/触及回踩区
    boolean inZone =
        price.compareTo(setup.getPullbackZoneHigh()) <= 0
            && price.compareTo(
                    setup
                        .getPullbackZoneLow()
                        .multiply(BigDecimal.ONE.subtract(props.getPullbackHardStopBufferPct())))
                >= 0;
    if (!inZone) {
      return new ScanResult(0, 0, 0, 0);
    }
    // 缩量：用最近完整日量 vs 涨停量（盘中近似用昨量）
    TradeStockDaily last = asc.get(asc.size() - 1);
    Long recentVol = last.getVolume();
    boolean shrinkOk =
        recentVol != null
            && setup.getLimitUpVolume() != null
            && setup.getLimitUpVolume() > 0
            && BigDecimal.valueOf(recentVol)
                    .divide(BigDecimal.valueOf(setup.getLimitUpVolume()), 4, RoundingMode.HALF_UP)
                    .compareTo(props.getPullbackVolMaxRatio())
                <= 0;
    // 收阳或站上 MA5
    boolean reclaim =
        last.getClosePrice() != null
            && last.getOpenPrice() != null
            && (last.getClosePrice().compareTo(last.getOpenPrice()) > 0
                || (ma.ma5() != null && price.compareTo(ma.ma5()) >= 0));
    if (!(shrinkOk && reclaim) && !(inZone && reclaim)) {
      // 触及区域但未确认：发 INFO（日去重）
      String title = "[趋势波段] " + display(watch) + " 触及回踩区";
      String content =
          String.format(
              "现价 %.2f 进入回踩区 [%.2f, %.2f]\n缩量确认=%s 收阳/站5日=%s\n请关注买入时机。",
              price, setup.getPullbackZoneLow(), setup.getPullbackZoneHigh(), shrinkOk, reclaim);
      var emit =
          notifier.emit(
              watch,
              "PULLBACK_ZONE",
              SwingConstants.LEVEL_INFO,
              title,
              content,
              price,
              "HOLD",
              null,
              null,
              setup.getId(),
              null,
              true);
      return new ScanResult(0, 0, emit.created() ? 1 : 0, 0);
    }

    BigDecimal hardStop =
        setup
            .getPullbackZoneLow()
            .multiply(BigDecimal.ONE.subtract(props.getPullbackHardStopBufferPct()))
            .setScale(2, RoundingMode.HALF_UP);
    int shares = sizer.calcShares(watch, price);
    String title = "[趋势波段·买入] " + display(watch) + " 回踩买点触发";
    String content =
        String.format(
            "**买点1 强势回踩**\n现价 %.2f\n建议买入 %d 股\n硬止损 %.2f（涨停低点下2%%）\n软止损 成本-8%%\n系统已自动记账，请同步手工下单。",
            price, shares, hardStop);
    var emit =
        notifier.emit(
            watch,
            SwingConstants.SIG_ENTRY_PULLBACK,
            SwingConstants.LEVEL_ACTION,
            title,
            content,
            price,
            "BUY",
            shares,
            hardStop,
            setup.getId(),
            null,
            true);
    if (!emit.created()) {
      return new ScanResult(0, 0, 0, 0);
    }
    signals++;
    setup.setStatus(SwingConstants.SETUP_TRIGGERED);
    setup.setTriggeredAt(LocalDateTime.now());
    setupRepository.save(setup);
    if (shares > 0) {
      openPosition(
          watch, setup, emit.signal(), price, shares, hardStop, props.getPullbackSoftStopPct());
      fills++;
    }
    transition(watch, SwingConstants.WATCH_HOLDING, "entry_pullback", setup.getId());
    return new ScanResult(0, 0, signals, fills);
  }

  private ScanResult tryTriggerBreakout(
      SwingWatchlist watch,
      SwingSetup setup,
      List<TradeStockDaily> asc,
      SwingIndicatorService.MaSnapshot ma,
      BigDecimal price,
      boolean eod) {
    if (setup.getPlatformHigh() == null) {
      return new ScanResult(0, 0, 0, 0);
    }
    // 放量：近5日均量
    Long vol5 = avgVolLast(asc, 5);
    TradeStockDaily last = asc.get(asc.size() - 1);
    boolean volOk =
        vol5 != null
            && vol5 > 0
            && last.getVolume() != null
            && BigDecimal.valueOf(last.getVolume())
                    .divide(BigDecimal.valueOf(vol5), 4, RoundingMode.HALF_UP)
                    .compareTo(props.getBreakoutVolMult())
                >= 0;
    boolean breakHigh = price.compareTo(setup.getPlatformHigh()) > 0;
    // 尾盘确认：eod 或 14:50 后；盘中突破只提醒
    boolean confirmWindow =
        eod || LocalDateTime.now().getHour() >= 14 && LocalDateTime.now().getMinute() >= 50;
    BigDecimal buffer =
        setup
            .getPlatformHigh()
            .multiply(BigDecimal.ONE.subtract(props.getBreakoutBufferPct()))
            .setScale(2, RoundingMode.HALF_UP);
    boolean retestHold = price.compareTo(buffer) >= 0 && breakHigh;

    if (!(breakHigh && volOk)) {
      return new ScanResult(0, 0, 0, 0);
    }
    if (!confirmWindow && !retestHold) {
      var emit =
          notifier.emit(
              watch,
              "BREAKOUT_INTRADAY",
              SwingConstants.LEVEL_INFO,
              "[趋势波段] " + display(watch) + " 盘中突破平台",
              String.format(
                  "现价 %.2f 突破平台高点 %.2f，放量=%s。等待尾盘确认或次日回踩不破。",
                  price, setup.getPlatformHigh(), volOk),
              price,
              "HOLD",
              null,
              null,
              setup.getId(),
              null,
              true);
      return new ScanResult(0, 0, emit.created() ? 1 : 0, 0);
    }

    BigDecimal hardStop =
        setup
            .getPlatformHigh()
            .multiply(BigDecimal.ONE.subtract(props.getBreakoutHardStopBufferPct()))
            .setScale(2, RoundingMode.HALF_UP);
    int shares = sizer.calcShares(watch, price);
    String title = "[趋势波段·买入] " + display(watch) + " 突破买点触发";
    String content =
        String.format(
            "**买点2 趋势突破**\n现价 %.2f\n平台高点 %.2f\n建议买入 %d 股\n硬止损 %.2f\n软止损 成本-10%%\n系统已自动记账，请同步手工下单。",
            price, setup.getPlatformHigh(), shares, hardStop);
    var emit =
        notifier.emit(
            watch,
            SwingConstants.SIG_ENTRY_BREAKOUT,
            SwingConstants.LEVEL_ACTION,
            title,
            content,
            price,
            "BUY",
            shares,
            hardStop,
            setup.getId(),
            null,
            true);
    if (!emit.created()) {
      return new ScanResult(0, 0, 0, 0);
    }
    setup.setStatus(SwingConstants.SETUP_TRIGGERED);
    setup.setTriggeredAt(LocalDateTime.now());
    setupRepository.save(setup);
    int fills = 0;
    if (shares > 0) {
      openPosition(
          watch, setup, emit.signal(), price, shares, hardStop, props.getBreakoutSoftStopPct());
      fills = 1;
    }
    transition(watch, SwingConstants.WATCH_HOLDING, "entry_breakout", setup.getId());
    return new ScanResult(0, 0, 1, fills);
  }

  private ScanResult managePosition(
      SwingWatchlist watch,
      SwingPosition pos,
      SwingIndicatorService.MaSnapshot ma,
      List<TradeStockDaily> asc,
      BigDecimal price,
      boolean eod) {
    int signals = 0;
    int fills = 0;
    if (price == null) {
      return new ScanResult(0, 0, 0, 0);
    }
    if (price.compareTo(pos.getPeakPrice()) > 0) {
      pos.setPeakPrice(price);
    }
    BigDecimal pnlPct =
        price.subtract(pos.getAvgCost()).divide(pos.getAvgCost(), 6, RoundingMode.HALF_UP);
    pos.setUnrealizedPnl(
        price.subtract(pos.getAvgCost()).multiply(BigDecimal.valueOf(pos.getShares())));
    updateProfitTier(pos, pnlPct);

    // 1) 硬止损 / 软止损
    if (price.compareTo(pos.getStopPrice()) <= 0
        || price.compareTo(pos.getHardStopPrice()) <= 0
        || (pos.getSoftStopPct() != null && pnlPct.compareTo(pos.getSoftStopPct().negate()) <= 0)) {
      String reason =
          price.compareTo(pos.getHardStopPrice()) <= 0
              ? "STOP_SUPPORT"
              : pnlPct.compareTo(
                          (pos.getSoftStopPct() == null
                                  ? props.getPullbackSoftStopPct()
                                  : pos.getSoftStopPct())
                              .negate())
                      <= 0
                  ? "STOP_PCT"
                  : "STOP_HARD";
      var emit =
          notifier.emit(
              watch,
              SwingConstants.SIG_STOP_HARD,
              SwingConstants.LEVEL_CRITICAL,
              "[趋势波段·止损] " + display(watch),
              String.format(
                  "现价 %.2f 触发止损（%s）\n成本 %.2f 止损价 %.2f\n系统已清仓记账，请立刻手工卖出全部仓位。",
                  price, reason, pos.getAvgCost(), pos.getStopPrice()),
              price,
              "SELL_ALL",
              pos.getShares(),
              pos.getStopPrice(),
              pos.getSetupId(),
              pos.getId(),
              true);
      if (emit.created()) {
        signals++;
        closeAll(watch, pos, emit.signal(), price, reason);
        fills++;
      }
      positionRepository.save(pos);
      return new ScanResult(0, 0, signals, fills);
    }

    // 2) 单日暴跌减半（相对昨收，无昨收则相对成本日内）
    BigDecimal dayDrop = dayDropPct(asc, price);
    if (dayDrop != null
        && dayDrop.compareTo(props.getSingleDayCrashPct()) >= 0
        && pnlPct.compareTo(props.getTrailTier3Profit()) > 0
        && pos.getPartialExits() == 0) {
      int sell = sizer.halfLots(pos.getShares());
      var emit =
          notifier.emit(
              watch,
              SwingConstants.SIG_CRASH_HALVE,
              SwingConstants.LEVEL_CRITICAL,
              "[趋势波段·减仓] " + display(watch) + " 高位单日急跌",
              String.format(
                  "单日跌幅约 %.1f%%，盈利已 >50%%。建议减半 %d 股。系统已自动减仓。",
                  dayDrop.multiply(BigDecimal.valueOf(100)), sell),
              price,
              "SELL_50",
              sell,
              pos.getStopPrice(),
              pos.getSetupId(),
              pos.getId(),
              true);
      if (emit.created() && sell > 0) {
        signals++;
        partialSell(watch, pos, emit.signal(), price, sell, "CRASH_HALVE");
        fills++;
      }
    }

    // 3) 分级移动止盈
    if (pos.getTrailDrawdownPct() != null
        && pos.getPeakPrice() != null
        && pnlPct.compareTo(props.getTrailTier1Profit()) >= 0) {
      BigDecimal trailLine =
          pos.getPeakPrice()
              .multiply(BigDecimal.ONE.subtract(pos.getTrailDrawdownPct()))
              .setScale(2, RoundingMode.HALF_UP);
      // 止损线上移
      if (pos.getLockedProfitPct() != null) {
        BigDecimal lockLine =
            pos.getAvgCost()
                .multiply(BigDecimal.ONE.add(pos.getLockedProfitPct()))
                .setScale(2, RoundingMode.HALF_UP);
        if (lockLine.compareTo(pos.getStopPrice()) > 0) {
          pos.setStopPrice(lockLine);
        }
      } else if (pnlPct.compareTo(props.getTrailTier1Profit()) >= 0
          && pos.getAvgCost().compareTo(pos.getStopPrice()) > 0) {
        pos.setStopPrice(pos.getAvgCost()); // 保本
      }
      if (price.compareTo(trailLine) <= 0) {
        boolean first = pos.getPartialExits() == 0;
        int sell = first ? sizer.halfLots(pos.getShares()) : pos.getShares();
        String action = first ? "SELL_50" : "SELL_ALL";
        var emit =
            notifier.emit(
                watch,
                SwingConstants.SIG_TRAIL_TP + (first ? "_1" : "_2"),
                SwingConstants.LEVEL_ACTION,
                "[趋势波段·止盈] " + display(watch),
                String.format(
                    "最高价 %.2f 回撤触发（允许回撤 %.1f%%）\n现价 %.2f\n建议 %s %d 股。系统已自动执行。",
                    pos.getPeakPrice(),
                    pos.getTrailDrawdownPct().multiply(BigDecimal.valueOf(100)),
                    price,
                    first ? "卖出50%" : "清仓",
                    sell),
                price,
                action,
                sell,
                trailLine,
                pos.getSetupId(),
                pos.getId(),
                true);
        if (emit.created() && sell > 0) {
          signals++;
          if (first && sell < pos.getShares()) {
            partialSell(watch, pos, emit.signal(), price, sell, "TRAIL_TP");
          } else {
            closeAll(watch, pos, emit.signal(), price, "TRAIL_TP");
          }
          fills++;
        }
      }
    }

    // 4) 趋势终极止盈（EOD）
    if (eod && ma != null) {
      boolean belowMa20 =
          ma.latestClose() != null
              && ma.ma20() != null
              && ma.latestClose().compareTo(ma.ma20()) < 0;
      if (belowMa20) {
        pos.setMa20BreakDays(pos.getMa20BreakDays() + 1);
      } else {
        pos.setMa20BreakDays(0);
      }
      if (pos.getMa20BreakDays() >= 2 || indicatorService.deathCrossMa10Ma20(asc)) {
        String reason = pos.getMa20BreakDays() >= 2 ? "MA20_EXIT" : "DEATH_CROSS";
        var emit =
            notifier.emit(
                watch,
                reason.equals("MA20_EXIT")
                    ? SwingConstants.SIG_MA20_EXIT
                    : SwingConstants.SIG_DEATH_CROSS,
                SwingConstants.LEVEL_CRITICAL,
                "[趋势波段·趋势止盈] " + display(watch),
                String.format(
                    "触发 %s。现价 %.2f / MA20 %.2f。系统已清仓，请手工同步卖出剩余仓位。", reason, price, ma.ma20()),
                price,
                "SELL_ALL",
                pos.getShares(),
                ma.ma20(),
                pos.getSetupId(),
                pos.getId(),
                true);
        if (emit.created()) {
          signals++;
          closeAll(watch, pos, emit.signal(), price, reason);
          fills++;
        }
      }
    }

    // 5) 加仓：盈利>10% 且回踩 MA5，仅一次
    if (pos.getAddCount() == 0
        && pnlPct.compareTo(props.getAddMinProfitPct()) >= 0
        && ma != null
        && ma.ma5() != null
        && price.compareTo(ma.ma5()) <= 0
        && price.compareTo(ma.ma5().multiply(BigDecimal.valueOf(0.985))) >= 0) {
      int addShares =
          BigDecimal.valueOf(pos.getInitialShares())
              .multiply(props.getAddSizeRatio())
              .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
              .intValue();
      addShares = (addShares / 100) * 100;
      if (addShares >= 100) {
        var emit =
            notifier.emit(
                watch,
                SwingConstants.SIG_ADD,
                SwingConstants.LEVEL_ACTION,
                "[趋势波段·加仓] " + display(watch),
                String.format(
                    "盈利已达 %.1f%% 且回踩5日线。建议加仓 %d 股，加仓后止损上移成本。系统已自动加仓。",
                    pnlPct.multiply(BigDecimal.valueOf(100)), addShares),
                price,
                "BUY",
                addShares,
                pos.getAvgCost(),
                pos.getSetupId(),
                pos.getId(),
                true);
        if (emit.created()) {
          signals++;
          addPosition(watch, pos, emit.signal(), price, addShares);
          fills++;
        }
      }
    }

    positionRepository.save(pos);
    return new ScanResult(0, 0, signals, fills);
  }

  private void updateProfitTier(SwingPosition pos, BigDecimal pnlPct) {
    if (pnlPct.compareTo(props.getTrailTier3Profit()) > 0) {
      pos.setProfitTier("T3");
      pos.setTrailDrawdownPct(props.getTrailDrawdownTier3());
      pos.setLockedProfitPct(props.getLockedProfitTier2());
    } else if (pnlPct.compareTo(props.getTrailTier2Profit()) >= 0) {
      pos.setProfitTier("T2");
      pos.setTrailDrawdownPct(props.getTrailDrawdownTier2());
      pos.setLockedProfitPct(props.getLockedProfitTier2());
    } else if (pnlPct.compareTo(props.getTrailTier1Profit()) >= 0) {
      pos.setProfitTier("T1");
      pos.setTrailDrawdownPct(props.getTrailDrawdownTier1());
      pos.setLockedProfitPct(null);
    } else {
      pos.setProfitTier("T0");
      pos.setTrailDrawdownPct(null);
    }
  }

  private void openPosition(
      SwingWatchlist watch,
      SwingSetup setup,
      SwingSignal signal,
      BigDecimal price,
      int shares,
      BigDecimal hardStop,
      BigDecimal softStopPct) {
    SwingPosition pos = new SwingPosition();
    pos.setWatchId(watch.getId());
    pos.setUserId(watch.getUserId());
    pos.setStockCode(watch.getStockCode());
    pos.setSetupId(setup.getId());
    pos.setSetupType(setup.getSetupType());
    pos.setStatus(SwingConstants.POS_OPEN);
    pos.setEntryTime(LocalDateTime.now());
    pos.setAvgCost(price);
    pos.setShares(shares);
    pos.setInitialShares(shares);
    pos.setPeakPrice(price);
    pos.setHardStopPrice(hardStop);
    pos.setStopPrice(hardStop);
    pos.setSoftStopPct(softStopPct);
    pos.setProfitTier("T0");
    pos = positionRepository.save(pos);

    SwingFill fill = new SwingFill();
    fill.setPositionId(pos.getId());
    fill.setWatchId(watch.getId());
    fill.setSide(SwingConstants.SIDE_BUY);
    fill.setReason("ENTRY");
    fill.setPrice(price);
    fill.setShares(shares);
    fill.setAmount(price.multiply(BigDecimal.valueOf(shares)));
    fill.setFillTime(LocalDateTime.now());
    fill.setSource(SwingConstants.SRC_AUTO);
    fill.setSignalId(signal.getId());
    fill.setNote("HYBRID 自动记账");
    fillRepository.save(fill);

    signal.setPositionId(pos.getId());
    signal.setStatus(SwingConstants.SIGNAL_EXECUTED);
    signal.setExecutedAt(LocalDateTime.now());
    signalRepository.save(signal);
  }

  private void partialSell(
      SwingWatchlist watch,
      SwingPosition pos,
      SwingSignal signal,
      BigDecimal price,
      int shares,
      String reason) {
    int sell = Math.min(shares, pos.getShares());
    BigDecimal pnl = price.subtract(pos.getAvgCost()).multiply(BigDecimal.valueOf(sell));
    pos.setRealizedPnl(nullToZero(pos.getRealizedPnl()).add(pnl));
    pos.setShares(pos.getShares() - sell);
    pos.setPartialExits(pos.getPartialExits() + 1);
    pos.setStatus(SwingConstants.POS_PARTIAL);
    transition(watch, SwingConstants.WATCH_HOLDING_PARTIAL, reason, pos.getId());

    SwingFill fill = new SwingFill();
    fill.setPositionId(pos.getId());
    fill.setWatchId(watch.getId());
    fill.setSide(SwingConstants.SIDE_SELL);
    fill.setReason(reason);
    fill.setPrice(price);
    fill.setShares(sell);
    fill.setAmount(price.multiply(BigDecimal.valueOf(sell)));
    fill.setFillTime(LocalDateTime.now());
    fill.setSource(SwingConstants.SRC_AUTO);
    fill.setSignalId(signal.getId());
    fillRepository.save(fill);

    signal.setStatus(SwingConstants.SIGNAL_EXECUTED);
    signal.setExecutedAt(LocalDateTime.now());
    signalRepository.save(signal);
  }

  private void closeAll(
      SwingWatchlist watch,
      SwingPosition pos,
      SwingSignal signal,
      BigDecimal price,
      String reason) {
    int sell = pos.getShares();
    if (sell > 0) {
      BigDecimal pnl = price.subtract(pos.getAvgCost()).multiply(BigDecimal.valueOf(sell));
      pos.setRealizedPnl(nullToZero(pos.getRealizedPnl()).add(pnl));
    }
    pos.setShares(0);
    pos.setStatus(SwingConstants.POS_CLOSED);
    pos.setExitTime(LocalDateTime.now());
    pos.setExitReason(reason);
    pos.setUnrealizedPnl(BigDecimal.ZERO);
    transition(watch, SwingConstants.WATCH_CLOSED, reason, pos.getId());

    if (sell > 0) {
      SwingFill fill = new SwingFill();
      fill.setPositionId(pos.getId());
      fill.setWatchId(watch.getId());
      fill.setSide(SwingConstants.SIDE_SELL);
      fill.setReason(reason);
      fill.setPrice(price);
      fill.setShares(sell);
      fill.setAmount(price.multiply(BigDecimal.valueOf(sell)));
      fill.setFillTime(LocalDateTime.now());
      fill.setSource(SwingConstants.SRC_AUTO);
      fill.setSignalId(signal.getId());
      fillRepository.save(fill);
    }
    signal.setStatus(SwingConstants.SIGNAL_EXECUTED);
    signal.setExecutedAt(LocalDateTime.now());
    signalRepository.save(signal);
  }

  private void addPosition(
      SwingWatchlist watch, SwingPosition pos, SwingSignal signal, BigDecimal price, int shares) {
    BigDecimal oldCost = pos.getAvgCost().multiply(BigDecimal.valueOf(pos.getShares()));
    BigDecimal addCost = price.multiply(BigDecimal.valueOf(shares));
    int newShares = pos.getShares() + shares;
    pos.setAvgCost(
        oldCost.add(addCost).divide(BigDecimal.valueOf(newShares), 2, RoundingMode.HALF_UP));
    pos.setShares(newShares);
    pos.setAddCount(pos.getAddCount() + 1);
    pos.setStopPrice(pos.getAvgCost()); // 加仓后止损上移成本
    pos.setHardStopPrice(pos.getAvgCost().min(pos.getHardStopPrice()));

    SwingFill fill = new SwingFill();
    fill.setPositionId(pos.getId());
    fill.setWatchId(watch.getId());
    fill.setSide(SwingConstants.SIDE_BUY);
    fill.setReason("ADD");
    fill.setPrice(price);
    fill.setShares(shares);
    fill.setAmount(addCost);
    fill.setFillTime(LocalDateTime.now());
    fill.setSource(SwingConstants.SRC_AUTO);
    fill.setSignalId(signal.getId());
    fillRepository.save(fill);

    signal.setStatus(SwingConstants.SIGNAL_EXECUTED);
    signal.setExecutedAt(LocalDateTime.now());
    signalRepository.save(signal);
  }

  private void expireActiveSetups(Long watchId, String reason) {
    List<SwingSetup> actives =
        setupRepository.findByWatchIdAndStatusInOrderByDetectedAtDesc(
            watchId, List.of(SwingConstants.SETUP_ACTIVE));
    for (SwingSetup s : actives) {
      s.setStatus(SwingConstants.SETUP_EXPIRED);
      s.setInvalidReason(reason);
      setupRepository.save(s);
    }
  }

  private void expireStaleSetups(SwingWatchlist watch) {
    List<SwingSetup> actives =
        setupRepository.findByWatchIdAndStatusInOrderByDetectedAtDesc(
            watch.getId(), List.of(SwingConstants.SETUP_ACTIVE));
    LocalDate today = LocalDate.now();
    for (SwingSetup s : actives) {
      if (s.getExpireDate() != null && today.isAfter(s.getExpireDate())) {
        s.setStatus(SwingConstants.SETUP_EXPIRED);
        s.setInvalidReason("超过有效期");
        setupRepository.save(s);
      }
    }
  }

  private void transition(SwingWatchlist watch, String to, String eventType, Long positionId) {
    String from = watch.getStatus();
    if (to.equals(from)) {
      return;
    }
    watch.setStatus(to);
    SwingEvent ev = new SwingEvent();
    ev.setWatchId(watch.getId());
    ev.setPositionId(positionId);
    ev.setEventType(eventType);
    ev.setFromStatus(from);
    ev.setToStatus(to);
    eventRepository.save(ev);
  }

  private BigDecimal resolvePrice(
      AStockDataQuoteService.QuoteSnapshot quote, SwingIndicatorService.MaSnapshot ma) {
    if (quote != null
        && quote.latestPrice() != null
        && quote.latestPrice().compareTo(BigDecimal.ZERO) > 0) {
      return quote.latestPrice();
    }
    return ma == null ? null : ma.latestClose();
  }

  private Long avgVolLast(List<TradeStockDaily> asc, int n) {
    if (asc.size() < n) {
      return null;
    }
    long sum = 0;
    int c = 0;
    for (TradeStockDaily d : asc.subList(asc.size() - n, asc.size())) {
      if (d.getVolume() != null) {
        sum += d.getVolume();
        c++;
      }
    }
    return c == 0 ? null : sum / c;
  }

  private BigDecimal dayDropPct(List<TradeStockDaily> asc, BigDecimal price) {
    if (asc.size() < 2 || price == null) {
      return null;
    }
    BigDecimal prevClose = asc.get(asc.size() - 2).getClosePrice();
    if (prevClose == null || prevClose.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    if (price.compareTo(prevClose) >= 0) {
      return BigDecimal.ZERO;
    }
    return prevClose.subtract(price).divide(prevClose, 6, RoundingMode.HALF_UP);
  }

  private String display(SwingWatchlist watch) {
    String name = watch.getStockName() == null ? "" : watch.getStockName();
    return name + "(" + watch.getStockCode() + ")";
  }

  private BigDecimal nullToZero(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
