package com.quant.service;

import com.quant.config.NotificationProperties;
import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.PositionFillRequest;
import com.quant.dto.techai.PositionFillDTO;
import com.quant.dto.techai.TechAiAlertDTO;
import com.quant.dto.techai.TechAiPoolItemDTO;
import com.quant.entity.InvestAlert;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.TechAiPool;
import com.quant.entity.TechAiPositionFill;
import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.TechAiPoolRepository;
import com.quant.repository.TechAiPositionFillRepository;
import com.quant.repository.TechAiQuoteSnapshotRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.service.techai.TechAiAlertCandidate;
import com.quant.service.techai.TechAiAlertRuleEngine;
import com.quant.service.techai.TechAiAlertThresholds;
import com.quant.service.techai.TechAiAtrCalculator;
import com.quant.service.techai.TechAiMarketContext;
import com.quant.service.techai.TechAiPositionEngine;
import com.quant.service.techai.TechAiStockCodeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/**
 * 短线 AI 监控：自 2026-06-17 起独立使用 tech_ai_pool / tech_ai_position_fill，
 * 不再读写 invest_stock_pool / invest_position_fill，与龙江投资彻底隔离。
 */
@Slf4j
@Service
public class TechAiService {

    private static final String POOL_TYPE_TECH_AI = "tech_ai";

    private final TechAiPoolRepository poolRepository;
    private final TechAiPositionFillRepository fillRepository;
    private final TradeStockBasicRepository basicRepository;
    private final TradeStockDailyRepository dailyRepository;
    private final TechAiQuoteSnapshotRepository quoteRepository;
    private final InvestAlertRepository alertRepository;
    private final InvestPositionCommonRepository positionRepository;
    private final TechAiAlertRuleEngine ruleEngine;
    private final TechAiPositionEngine positionEngine;
    private final TechAiAtrCalculator atrCalculator;
    private final AStockDataQuoteService aStockDataQuoteService;
    private final NotificationService notificationService;
    private final NotificationProperties notificationProperties;
    /** 2026-06-30 Monitor Fusion — 用于在每次 schedule 中追加评估固定价/ATR/止盈止损 */
    private final com.quant.service.monitor.MonitorService monitorService;

    public TechAiService(TechAiPoolRepository poolRepository,
                         TechAiPositionFillRepository fillRepository,
                         TradeStockBasicRepository basicRepository,
                         TradeStockDailyRepository dailyRepository,
                         TechAiQuoteSnapshotRepository quoteRepository,
                         InvestAlertRepository alertRepository,
                         InvestPositionCommonRepository positionRepository,
                         TechAiAlertRuleEngine ruleEngine,
                         TechAiPositionEngine positionEngine,
                         TechAiAtrCalculator atrCalculator,
                         AStockDataQuoteService aStockDataQuoteService,
                         NotificationService notificationService,
                         NotificationProperties notificationProperties,
                         com.quant.service.monitor.MonitorService monitorService) {
        this.poolRepository = poolRepository;
        this.fillRepository = fillRepository;
        this.basicRepository = basicRepository;
        this.dailyRepository = dailyRepository;
        this.quoteRepository = quoteRepository;
        this.alertRepository = alertRepository;
        this.positionRepository = positionRepository;
        this.ruleEngine = ruleEngine;
        this.positionEngine = positionEngine;
        this.atrCalculator = atrCalculator;
        this.aStockDataQuoteService = aStockDataQuoteService;
        this.notificationService = notificationService;
        this.notificationProperties = notificationProperties;
        this.monitorService = monitorService;
    }

    /**
     * 获取持仓记录（若不存在则创建空白记录）。
     */
    private InvestPositionCommon getOrCreatePosition(String stockCode) {
        return positionRepository.findByStockCodeAndPoolType(stockCode, POOL_TYPE_TECH_AI)
                .orElseGet(() -> {
                    InvestPositionCommon pos = new InvestPositionCommon();
                    pos.setStockCode(stockCode);
                    pos.setPoolType(POOL_TYPE_TECH_AI);
                    pos.setStatus("watching");
                    pos.setAlertState("none");
                    pos.setPositionState("none");
                    pos.setPositionLots(BigDecimal.ZERO);
                    pos.setRealizedPnl(BigDecimal.ZERO);
                    pos.setAddCount(0);
                    pos.setTakeProfitDone(0);
                    pos.setBreakevenAfterTp(1);
                    pos.setUseAtr(0);
                    return pos;
                });
    }

    @Transactional(readOnly = true)
    public List<TechAiPoolItemDTO> listPool() {
        List<TechAiPool> pool = poolRepository.findAllByOrderByCreatedAtDesc();
        if (pool.isEmpty()) {
            return List.of();
        }
        List<String> codes = pool.stream().map(TechAiPool::getStockCode).toList();
        Map<String, TechAiQuoteSnapshot> quotes = latestQuotes(codes);
        Map<String, TradeStockBasic> basics = basics(codes);
        // 批量获取持仓状态
        Map<String, InvestPositionCommon> posMap = positionRepository.findByStockCodeIn(codes).stream()
                .collect(Collectors.toMap(InvestPositionCommon::getStockCode, p -> p, (a, b) -> a));
        return pool.stream()
                .map(item -> toPoolDTO(item, posMap.get(item.getStockCode()),
                        basicFromMap(basics, item.getStockCode()), quotes.get(item.getStockCode())))
                .toList();
    }

    @Transactional
    public TechAiPoolItemDTO addToPool(PoolSaveRequest request) {
        String keyword = request.getKeyword() == null ? "" : request.getKeyword().trim();
        if (keyword.isBlank()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        String stockCode = resolveStockCode(keyword);
        Optional<TechAiPool> existing = poolRepository.findByStockCode(stockCode);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("该股票已在监控池中：" + stockCode);
        }

        TechAiPool pool = new TechAiPool();
        pool.setStockCode(stockCode);
        TradeStockBasic basic = basic(stockCode);
        if (basic != null) {
            pool.setStockName(basic.getStockName());
        }
        pool.setStatus(request.getStatus() == null || request.getStatus().isBlank() ? "watching" : request.getStatus());
        pool.setMemo(request.getMemo());
        TechAiPool saved = poolRepository.save(pool);
        InvestPositionCommon pos = getOrCreatePosition(saved.getStockCode());
        return toPoolDTO(saved, pos, basic, quoteRepository.findFirstByStockCodeOrderByQuoteTimeDesc(saved.getStockCode()).orElse(null));
    }

    @Transactional
    public TechAiPoolItemDTO updateField(Integer id, PoolFieldUpdateRequest request) {
        TechAiPool pool = poolRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + id));
        String field = request.getField() == null ? "" : request.getField().trim();
        String value = request.getValue();
        boolean blank = value == null || value.isBlank();
        switch (field) {
            case "status" -> pool.setStatus(blank ? "watching" : value.trim());
            case "memo" -> pool.setMemo(blank ? null : value);
            case "alertMinute1mPct", "alertMinute5mPct", "alertDailyPct",
                 "alertThreeDayPct", "alertTurnoverRatioPct",
                 "addStepPct", "trailPct", "addSizeSchedule", "maxLots",
                 "takeProfitPct", "breakevenAfterTp", "timeStopDays",
                 "useAtr", "atrPeriod", "atrAddMult", "atrTrailMult",
                 "targetSellPrice" -> {
                InvestPositionCommon pos = getOrCreatePosition(pool.getStockCode());
                applyFieldToPosition(pos, field, value, blank);
                positionRepository.save(pos);
            }
            default -> throw new IllegalArgumentException("不支持的字段：" + field);
        }
        TechAiPool saved = poolRepository.save(pool);
        InvestPositionCommon pos = positionRepository.findByStockCodeAndPoolType(saved.getStockCode(), POOL_TYPE_TECH_AI).orElse(null);
        return toPoolDTO(saved, pos, basic(saved.getStockCode()), quoteRepository.findFirstByStockCodeOrderByQuoteTimeDesc(saved.getStockCode()).orElse(null));
    }

    @Transactional
    public void removeFromPool(Integer id) {
        TechAiPool pool = poolRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + id));
        String stockCode = pool.getStockCode();
        fillRepository.deleteByPoolId(pool.getId());
        poolRepository.delete(pool);
        // 同时删除 invest_position_common 中的记录
        positionRepository.findByStockCodeAndPoolType(stockCode, POOL_TYPE_TECH_AI)
                .ifPresent(positionRepository::delete);
    }

    @Transactional
    public TechAiPoolItemDTO recordFill(Integer poolId, PositionFillRequest request) {
        TechAiPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + poolId));
        String action = request.getAction() == null ? "" : request.getAction().trim().toLowerCase();
        if (!List.of("open", "add", "reduce", "clear").contains(action)) {
            throw new IllegalArgumentException("不支持的操作：" + action);
        }
        if (request.getPrice() == null || request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("成交价必须大于 0");
        }
        InvestPositionCommon position = getOrCreatePosition(pool.getStockCode());
        BigDecimal currentLots = position.getPositionLots() == null ? BigDecimal.ZERO : position.getPositionLots();
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

        TechAiPositionFill fill = new TechAiPositionFill();
        fill.setPoolId(pool.getId());
        fill.setStockCode(pool.getStockCode());
        fill.setAction(action);
        fill.setPrice(request.getPrice());
        fill.setLots(lots);
        fill.setAmount(request.getPrice().multiply(lots)
                .multiply(BigDecimal.valueOf(TechAiPositionEngine.SHARES_PER_LOT)).setScale(2, RoundingMode.HALF_UP));
        fill.setFee(request.getFee());
        fill.setNote(request.getNote());
        fill.setFilledAt(request.getFilledAt() == null ? LocalDateTime.now() : request.getFilledAt());
        fillRepository.save(fill);

        recomputeAggregates(position);
        positionRepository.save(position);
        TechAiPool saved = poolRepository.save(pool);
        return toPoolDTO(saved, position, basic(saved.getStockCode()),
                quoteRepository.findFirstByStockCodeOrderByQuoteTimeDesc(saved.getStockCode()).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<PositionFillDTO> listFills(Integer poolId) {
        TechAiPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + poolId));
        return fillRepository.findByPoolIdOrderByFilledAtDescIdDesc(poolId).stream()
                .map(this::toFillDTO)
                .toList();
    }

    @Transactional
    public TechAiPoolItemDTO deleteFill(Integer poolId, Long fillId) {
        TechAiPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new IllegalArgumentException("监控池条目不存在：" + poolId));
        TechAiPositionFill fill = fillRepository.findById(fillId)
                .orElseThrow(() -> new IllegalArgumentException("成交记录不存在：" + fillId));
        if (!fill.getPoolId().equals(poolId)) {
            throw new IllegalArgumentException("成交记录与标的不匹配");
        }
        fillRepository.delete(fill);
        InvestPositionCommon position = getOrCreatePosition(pool.getStockCode());
        recomputeAggregates(position);
        positionRepository.save(position);
        TechAiPool saved = poolRepository.save(pool);
        return toPoolDTO(saved, position, basic(saved.getStockCode()),
                quoteRepository.findFirstByStockCodeOrderByQuoteTimeDesc(saved.getStockCode()).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<TechAiAlertDTO> listAlerts() {
        List<String> codes = poolRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(TechAiPool::getStockCode)
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
        List<TechAiPool> pool = poolRepository.findByStatusNotOrderByCreatedAtDesc("exited");
        if (pool.isEmpty()) {
            return 0;
        }
        List<String> codes = pool.stream().map(TechAiPool::getStockCode).toList();
        Map<String, TechAiQuoteSnapshot> quotes = latestQuotes(codes);
        Map<String, TradeStockBasic> basics = basics(codes);
        int triggered = 0;
        for (TechAiPool item : pool) {
            TechAiQuoteSnapshot quote = quotes.get(item.getStockCode());
            if (quote == null) {
                continue;
            }
            InvestPositionCommon position = positionRepository
                    .findByStockCodeAndPoolType(item.getStockCode(), POOL_TYPE_TECH_AI).orElse(null);
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
            log.info("短线AI行情监控触发 {} 条告警", triggered);
        }
        // 2026-06-30 Monitor Fusion: 追加评估固定价/ATR/止盈止损 (不同 signal type，与既有 % 提醒并存)
        try {
            triggered += monitorService.scan(POOL_TYPE_TECH_AI);
        } catch (Exception e) {
            log.warn("MonitorService.scan 异常（忽略）: {}", e.getMessage());
        }
        return triggered;
    }

    /** 收盘确认：用 a-stock-data 实时收盘价判定持仓信号并推送（两段式中的确认段）。
     *  注意：实时价/收盘价统一走 a-stock-data 实时接口，trade_stock_daily 同步延迟且不准确。*/
    @Scheduled(cron = "${notification.position-confirm.cron:0 5 15 * * MON-FRI}")
    @Transactional
    public int confirmPositionSignals() {
        NotificationProperties.QuoteMonitor cfg = notificationProperties.getQuoteMonitor();
        if (!cfg.isEnabled()) {
            return 0;
        }
        List<TechAiPool> pool = poolRepository.findByStatusNotOrderByCreatedAtDesc("exited");
        if (pool.isEmpty()) {
            return 0;
        }
        // 一次性批量拉实时行情，避免 N 次串行 HTTP
        Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap = aStockDataQuoteService.fetchQuotes(
                pool.stream().map(TechAiPool::getStockCode).toList());
        int triggered = 0;
        for (TechAiPool item : pool) {
            InvestPositionCommon position = positionRepository
                    .findByStockCodeAndPoolType(item.getStockCode(), POOL_TYPE_TECH_AI).orElse(null);
            if (position == null || position.getPositionLots() == null
                    || position.getPositionLots().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            AStockDataQuoteService.QuoteSnapshot snapshot = quoteMap.get(
                    item.getStockCode() == null ? "" : item.getStockCode().trim().toUpperCase(Locale.ROOT));
            if (snapshot == null || snapshot.latestPrice() == null || snapshot.latestPrice().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal close = snapshot.latestPrice();
            // 历史 K 线仍来自 trade_stock_daily（用于峰值参考与 ATR）
            List<TradeStockDaily> recentKline = dailyRepository.findTop6ByStockCodeOrderByTradeDateDesc(item.getStockCode());
            BigDecimal historicalHigh = recentKline.isEmpty() ? null : recentKline.get(0).getHighPrice();
            BigDecimal atr = isAtrMode(position) ? atrFor(position, item.getStockCode()) : null;
            BigDecimal peak = position.getPeakPrice() == null ? close : position.getPeakPrice();
            if (historicalHigh != null) {
                peak = peak.max(historicalHigh);
            }
            peak = peak.max(close);
            position.setPeakPrice(peak);
            TechAiPositionEngine.PositionPlan plan = positionEngine.evaluate(
                    TechAiPositionEngine.from(position), close, atr);
            position.setStopPrice(plan.getStopPrice());
            positionRepository.save(position);
            if (plan.getPendingSignal() != null && pushPositionSignal(item, position, close, plan, true, cfg)) {
                triggered++;
            }
        }
        if (triggered > 0) {
            log.info("短线AI收盘确认触发 {} 条持仓信号", triggered);
        }
        return triggered;
    }

    private int evaluateIntradayPosition(TechAiPool item, InvestPositionCommon position, TechAiQuoteSnapshot quote, NotificationProperties.QuoteMonitor cfg) {
        if (position == null || position.getPositionLots() == null
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
        TechAiPositionEngine.PositionPlan plan = positionEngine.evaluate(
                TechAiPositionEngine.from(position), price, atr);
        position.setStopPrice(plan.getStopPrice());
        positionRepository.save(position);
        if (plan.getPendingSignal() == null) {
            return 0;
        }
        return pushPositionSignal(item, position, price, plan, false, cfg) ? 1 : 0;
    }

    private boolean pushPositionSignal(TechAiPool item, InvestPositionCommon position, BigDecimal price,
                                       TechAiPositionEngine.PositionPlan plan, boolean confirm,
                                       NotificationProperties.QuoteMonitor cfg) {
        String signal = plan.getPendingSignal();
        String signalType = "position_" + signal.toLowerCase() + (confirm ? "_confirm" : "_warn");
        if (!shouldPushPosition(item.getStockCode(), signalType, confirm, cfg)) {
            return false;
        }
        String stockName = displayStockName(item, basic(item.getStockCode()));
        String phase = confirm ? "收盘确认" : "盘中预警";
        String actionLabel = switch (signal) {
            case TechAiPositionEngine.SIGNAL_STOP -> "清仓信号";
            case TechAiPositionEngine.SIGNAL_ADD -> "加仓信号";
            case TechAiPositionEngine.SIGNAL_TP -> "止盈信号";
            default -> "持仓信号";
        };
        String title = String.format("【%s·%s】%s(%s) @ %s",
                actionLabel, phase, stockName, item.getStockCode(), fmt(price));
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

    private boolean shouldPushPosition(String stockCode, String signalType, boolean confirm,
                                       NotificationProperties.QuoteMonitor cfg) {
        LocalDateTime now = LocalDateTime.now();
        if (!confirm) {
            return alertRepository.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(stockCode, signalType)
                    .map(a -> a.getTriggerAt() == null
                            || a.getTriggerAt().plusMinutes(cfg.getCooldownMinutes()).isBefore(now))
                    .orElse(true);
        }
        LocalDate today = LocalDate.now();
        return !alertRepository.existsByStockCodeAndSignalTypeAndTriggerAtBetween(
                stockCode, signalType, today.atStartOfDay(), today.plusDays(1).atStartOfDay().minusNanos(1));
    }

    private int positionLevel(String signal) {
        return switch (signal) {
            case TechAiPositionEngine.SIGNAL_STOP -> 3;
            case TechAiPositionEngine.SIGNAL_ADD, TechAiPositionEngine.SIGNAL_TP -> 2;
            default -> 1;
        };
    }

    private String buildPositionContent(TechAiPool item, InvestPositionCommon position, String stockName, BigDecimal price,
                                        TechAiPositionEngine.PositionPlan plan, String signal, String phase) {
        String advice = switch (signal) {
            case TechAiPositionEngine.SIGNAL_STOP -> "现价已触及移动止损，建议清仓离场。";
            case TechAiPositionEngine.SIGNAL_ADD -> String.format("现价突破加仓位，建议加仓 %s 手。",
                    plan.getNextAddLots() == null ? "-" : fmt(plan.getNextAddLots()));
            case TechAiPositionEngine.SIGNAL_TP -> String.format("现价达到目标价，建议减仓 %s%% 止盈。",
                    position == null || position.getTakeProfitPct() == null ? "50" : fmt(position.getTakeProfitPct()));
            default -> "";
        };
        String warn = plan.isStopBelowCost() ? "\n\n> ⚠️ 当前止损价低于平均成本，触发止损将产生亏损。" : "";
        return String.format("""
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
                stockName, item.getStockCode(), phase,
                advice,
                fmt(price),
                fmt(position != null ? position.getAvgCost() : null),
                fmt(position != null ? position.getPositionLots() : null),
                fmt(plan.getStopPrice()),
                fmt(plan.getNextAddPrice()),
                fmt(plan.getTargetPrice()),
                fmt(plan.getFloatingPnl()), fmt(plan.getFloatingPnlPct()), warn);
    }

    private String fmt(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    private void recomputeAggregates(InvestPositionCommon position) {
        List<TechAiPositionFill> fills = fillRepository.findByPoolIdOrderByFilledAtAscIdAsc(
                positionRepository.findByStockCodeAndPoolType(position.getStockCode(), POOL_TYPE_TECH_AI)
                        .flatMap(p -> poolRepository.findByStockCode(p.getStockCode()).map(TechAiPool::getId))
                        .orElseThrow(() -> new IllegalStateException("pool not found for " + position.getStockCode())));
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

        for (TechAiPositionFill fill : fills) {
            String action = fill.getAction();
            BigDecimal price = fill.getPrice();
            BigDecimal fl = fill.getLots();
            if ("open".equals(action) || "add".equals(action)) {
                if (lots.compareTo(BigDecimal.ZERO) <= 0) {
                    avg = price;
                    lots = fl;
                    entry = price;
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
                    realized = realized.add(price.subtract(avg)
                            .multiply(sellLots).multiply(BigDecimal.valueOf(TechAiPositionEngine.SHARES_PER_LOT)));
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
            position.setStatus("exited");
            return;
        }

        position.setAvgCost(avg.setScale(2, RoundingMode.HALF_UP));
        position.setEntryPrice(entry);
        position.setLastAddPrice(lastBuyPrice);
        BigDecimal effectivePeak = peak == null ? entry : peak;
        position.setPeakPrice(effectivePeak);
        position.setTotalInvested(avg.multiply(lots)
                .multiply(BigDecimal.valueOf(TechAiPositionEngine.SHARES_PER_LOT)).setScale(2, RoundingMode.HALF_UP));
        position.setOpenedAt(openedAt);
        position.setTakeProfitDone(tpDone ? 1 : 0);
        position.setPositionState(scaled ? "scaled" : "holding");
        position.setStatus("holding");

        BigDecimal atr = isAtrMode(position) ? atrFor(position, position.getStockCode()) : null;
        TechAiPositionEngine.PositionPlan plan = positionEngine.evaluate(
                TechAiPositionEngine.from(position), effectivePeak, atr);
        position.setStopPrice(plan.getStopPrice());
    }

    private PositionFillDTO toFillDTO(TechAiPositionFill fill) {
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

    private TechAiMarketContext buildContext(String stockCode, String stockName, TechAiQuoteSnapshot quote) {
        List<TradeStockDaily> recent = dailyRepository.findTop6ByStockCodeOrderByTradeDateDesc(stockCode);
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

    private boolean shouldPush(TechAiAlertCandidate candidate, NotificationProperties.QuoteMonitor cfg) {
        String signalType = signalType(candidate);
        LocalDateTime now = LocalDateTime.now();
        if (candidate.minuteRule()) {
            return alertRepository.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(candidate.stockCode(), signalType)
                    .map(alert -> alert.getTriggerAt() == null
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
        alert.setSignalType(signalType(candidate));
        alert.setLevel(level(candidate));
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

    private int level(TechAiAlertCandidate candidate) {
        BigDecimal abs = candidate.threshold().abs();
        if (abs.compareTo(BigDecimal.valueOf(20)) >= 0 || abs.compareTo(BigDecimal.valueOf(300)) >= 0) return 3;
        if (abs.compareTo(BigDecimal.valueOf(7)) >= 0 || abs.compareTo(BigDecimal.valueOf(200)) >= 0) return 2;
        return 1;
    }

    private String signalType(TechAiAlertCandidate candidate) {
        return candidate.ruleType() + ":" + candidate.threshold().stripTrailingZeros().toPlainString();
    }

    private BigDecimal averageTurnover(List<TradeStockDaily> records) {
        List<BigDecimal> values = records.stream()
                .map(TradeStockDaily::getTurnoverRate)
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private TechAiPoolItemDTO toPoolDTO(TechAiPool item, InvestPositionCommon pos, TradeStockBasic basic, TechAiQuoteSnapshot quote) {
        BigDecimal dailyChange = quote == null ? null : pctChange(quote.getLatestPrice(), quote.getPrevClosePrice());
        BigDecimal price = quote == null ? null : quote.getLatestPrice();
        BigDecimal atr = isAtrMode(pos) ? atrFor(pos, item.getStockCode()) : null;
        TechAiPositionEngine.PositionPlan plan = positionEngine.evaluate(
                TechAiPositionEngine.from(pos), price, atr);
        return TechAiPoolItemDTO.builder()
                .id(item.getId())
                .stockCode(item.getStockCode())
                .qmtCode(TechAiStockCodeUtils.toQmtCode(item.getStockCode()))
                .stockName(displayStockName(item, basic))
                .status(item.getStatus())
                .memo(item.getMemo())
                .latestPrice(price)
                .dailyChangePct(dailyChange)
                .turnoverRate(quote == null ? null : quote.getTurnoverRate())
                .volume(quote == null ? null : quote.getVolume())
                .quoteTime(quote == null ? null : quote.getQuoteTime())
                // 告警阈值（从 position 读取）
                .alertMinute1mPct(pos != null ? pos.getAlertMinute1mPct() : null)
                .alertMinute5mPct(pos != null ? pos.getAlertMinute5mPct() : null)
                .alertDailyPct(pos != null ? pos.getAlertDailyPct() : null)
                .alertThreeDayPct(pos != null ? pos.getAlertThreeDayPct() : null)
                .alertTurnoverRatioPct(pos != null ? pos.getAlertTurnoverRatioPct() : null)
                // 持仓聚合（从 position 读取）
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
                .takeProfitDone(pos != null && pos.getTakeProfitDone() != null && pos.getTakeProfitDone() == 1)
                .openedAt(pos != null ? pos.getOpenedAt() : null)
                // 策略参数（从 position 读取）
                .addStepPct(pos != null ? pos.getAddStepPct() : null)
                .trailPct(pos != null ? pos.getTrailPct() : null)
                .addSizeSchedule(pos != null ? pos.getAddSizeSchedule() : null)
                .maxLots(pos != null ? pos.getMaxLots() : null)
                .takeProfitPct(pos != null ? pos.getTakeProfitPct() : null)
                .breakevenAfterTp(pos != null && pos.getBreakevenAfterTp() != null && pos.getBreakevenAfterTp() == 1)
                .timeStopDays(pos != null ? pos.getTimeStopDays() : null)
                .useAtr(isAtrMode(pos))
                .atrPeriod(pos != null ? pos.getAtrPeriod() : null)
                .atrAddMult(pos != null ? pos.getAtrAddMult() : null)
                .atrTrailMult(pos != null ? pos.getAtrTrailMult() : null)
                .targetSellPrice(pos != null ? pos.getTargetSellPrice() : null)
                // 实时计算
                .nextAddPrice(plan.getNextAddPrice())
                .nextAddLots(plan.getNextAddLots())
                .currentStopPrice(plan.getStopPrice())
                .floatingPnl(plan.getFloatingPnl())
                .floatingPnlPct(plan.getFloatingPnlPct())
                .atrValue(atr)
                .stopBelowCost(plan.isStopBelowCost())
                .pendingSignal(plan.getPendingSignal())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }

    private boolean isAtrMode(InvestPositionCommon pos) {
        return pos != null && pos.getUseAtr() != null && pos.getUseAtr() == 1;
    }

    private BigDecimal atrFor(InvestPositionCommon pos, String stockCode) {
        int period = pos.getAtrPeriod() == null || pos.getAtrPeriod() <= 0 ? 14 : pos.getAtrPeriod();
        List<TradeStockDaily> recent = dailyRepository.findTop30ByStockCodeOrderByTradeDateDesc(stockCode);
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

    private String displayStockName(TechAiPool item, TradeStockBasic basic) {
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
        return value.subtract(base)
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
        List<String> candidates = codeCandidates(List.of(stockCode));
        for (String candidate : candidates) {
            Optional<TradeStockBasic> basic = basicRepository.findByStockCode(candidate);
            if (basic.isPresent()) {
                return basic.get();
            }
        }
        return null;
    }

    private Map<String, TradeStockBasic> basics(Collection<String> codes) {
        Map<String, TradeStockBasic> result = new HashMap<>();
        List<String> candidates = codeCandidates(codes);
        for (TradeStockBasic basic : basicRepository.findByStockCodeIn(candidates)) {
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
        return quoteRepository.findLatestByStockCodes(codes).stream()
                .collect(Collectors.toMap(TechAiQuoteSnapshot::getStockCode, q -> q, (a, b) -> a));
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

    private void applyFieldToPosition(InvestPositionCommon pos, String field, String value, boolean blank) {
        switch (field) {
            case "alertMinute1mPct" -> pos.setAlertMinute1mPct(parsePositiveDecimal(value, field));
            case "alertMinute5mPct" -> pos.setAlertMinute5mPct(parsePositiveDecimal(value, field));
            case "alertDailyPct" -> pos.setAlertDailyPct(parsePositiveDecimal(value, field));
            case "alertThreeDayPct" -> pos.setAlertThreeDayPct(parsePositiveDecimal(value, field));
            case "alertTurnoverRatioPct" -> pos.setAlertTurnoverRatioPct(parsePositiveDecimal(value, field));
            case "addStepPct" -> pos.setAddStepPct(parsePositiveDecimal(value, field));
            case "trailPct" -> pos.setTrailPct(parsePositiveDecimal(value, field));
            case "addSizeSchedule" -> pos.setAddSizeSchedule(blank ? "1,1,1" : value.trim());
            case "maxLots" -> pos.setMaxLots(parsePositiveDecimal(value, field));
            case "takeProfitPct" -> {
                pos.setTakeProfitPct(parsePositiveDecimal(value, field));
                if (pos.getTargetSellPrice() == null && pos.getEntryPrice() != null) {
                    BigDecimal pct = pos.getTakeProfitPct();
                    if (pct != null) {
                        pos.setTargetSellPrice(pos.getEntryPrice()
                                .multiply(BigDecimal.ONE.add(pct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                                .setScale(2, RoundingMode.HALF_UP));
                    }
                }
            }
            case "breakevenAfterTp" -> pos.setBreakevenAfterTp(parseFlag(value));
            case "timeStopDays" -> pos.setTimeStopDays(parsePositiveInteger(value, field));
            case "useAtr" -> pos.setUseAtr(parseFlag(value));
            case "atrPeriod" -> pos.setAtrPeriod(parsePositiveInteger(value, field));
            case "atrAddMult" -> pos.setAtrAddMult(parsePositiveDecimal(value, field));
            case "atrTrailMult" -> pos.setAtrTrailMult(parsePositiveDecimal(value, field));
            case "targetSellPrice" -> pos.setTargetSellPrice(parsePositiveDecimal(value, field));
            default -> { /* ignore */ }
        }
    }
}