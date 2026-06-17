package com.quant.service;

import com.quant.config.InvestBigYangProperties;
import com.quant.dto.invest.BigYangAlertDTO;
import com.quant.dto.invest.BigYangRunResultDTO;
import com.quant.dto.invest.BigYangSignalDTO;
import com.quant.dto.invest.BigYangSummaryDTO;
import com.quant.entity.InvestAlert;
import com.quant.entity.InvestBigYangSignal;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestBigYangSignalRepository;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvestBigYangSignalService {

    static final String SIGNAL_STATUS_WATCHING = "watching";
    static final String SIGNAL_STATUS_TRIGGERED = "triggered";
    static final String SIGNAL_STATUS_EXPIRED = "expired";
    static final String ALERT_SIGNAL_TYPE = "BIG_YANG_BUY_TRIGGER";
    private static final Set<String> SOURCE_POOL_TYPES = Set.of("quality", "tech_vc");

    private final InvestBigYangSignalRepository signalRepository;
    private final InvestStockPoolRepository poolRepository;
    private final InvestAlertRepository alertRepository;
    private final TradeStockDailyRepository dailyRepository;
    private final EastMoneyRealtimeQuoteService eastMoneyRealtimeQuoteService;
    private final SinaRealtimeQuoteService sinaRealtimeQuoteService;
    private final InvestBigYangProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Transactional(readOnly = true)
    public BigYangSummaryDTO summary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        return BigYangSummaryDTO.builder()
                .unreadAlertCount(alertRepository.countBySignalTypeAndReadFlag(ALERT_SIGNAL_TYPE, 0))
                .watchingCount(signalRepository.countBySignalStatus(SIGNAL_STATUS_WATCHING))
                .triggeredCount(signalRepository.countBySignalStatus(SIGNAL_STATUS_TRIGGERED))
                .expiredCount(signalRepository.countBySignalStatus(SIGNAL_STATUS_EXPIRED))
                .todayNewWatchingCount(signalRepository.countBySignalStatusAndCreatedAtGreaterThanEqual(SIGNAL_STATUS_WATCHING, todayStart))
                .todayTriggeredCount(signalRepository.countBySignalStatusAndTriggerDate(SIGNAL_STATUS_TRIGGERED, LocalDate.now()))
                .build();
    }

    @Transactional(readOnly = true)
    public List<BigYangSignalDTO> signals() {
        List<InvestBigYangSignal> signals = signalRepository.findTop200ByOrderByUpdatedAtDescIdDesc();
        Map<String, TradeStockDaily> latestDailyMap = latestDailyMap(signals.stream().map(InvestBigYangSignal::getStockCode).toList());
        return signals.stream()
                .sorted(Comparator
                        .comparingInt((InvestBigYangSignal signal) -> statusOrder(signal.getSignalStatus()))
                        .thenComparing(InvestBigYangSignal::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(signal -> toSignalDTO(signal, latestDailyMap.get(signal.getStockCode())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BigYangAlertDTO> alerts() {
        Map<String, InvestBigYangSignal> latestSignals = signalRepository.findTop200ByOrderByUpdatedAtDescIdDesc().stream()
                .collect(Collectors.toMap(InvestBigYangSignal::getStockCode, signal -> signal, (a, b) -> a));
        return alertRepository.findTop100BySignalTypeOrderByTriggerAtDesc(ALERT_SIGNAL_TYPE).stream()
                .map(alert -> toAlertDTO(alert, latestSignals.get(alert.getStockCode())))
                .toList();
    }

    @Transactional
    public void markAlertRead(Long id) {
        InvestAlert alert = alertRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("提示消息不存在: " + id));
        if (!ALERT_SIGNAL_TYPE.equals(alert.getSignalType())) {
            throw new IllegalArgumentException("仅支持标记大阳线提示消息");
        }
        alert.setReadFlag(1);
        alertRepository.save(alert);
    }

    public BigYangRunResultDTO runManual() {
        return runScan(true, true, "manual");
    }

    public BigYangRunResultDTO runCandidateScan() {
        return runScan(true, false, "candidate");
    }

    public BigYangRunResultDTO runTriggerScan() {
        return runScan(false, true, "trigger");
    }

    private BigYangRunResultDTO runScan(boolean scanCandidates, boolean scanTriggers, String reason) {
        if (!properties.isEnabled()) {
            return BigYangRunResultDTO.builder()
                    .reason(reason)
                    .ranAt(LocalDateTime.now())
                    .message("大阳线战法未启用")
                    .build();
        }
        if (!running.compareAndSet(false, true)) {
            return BigYangRunResultDTO.builder()
                    .reason(reason)
                    .ranAt(LocalDateTime.now())
                    .message("大阳线战法扫描进行中，已跳过重复触发")
                    .build();
        }
        try {
            int createdWatching = 0;
            int triggered = 0;
            int expired = 0;
            if (scanCandidates) {
                createdWatching = scanCandidatesInternal();
            }
            if (scanTriggers) {
                TriggerResult triggerResult = scanTriggersInternal();
                triggered = triggerResult.triggeredCount();
                expired = triggerResult.expiredCount();
            }
            return BigYangRunResultDTO.builder()
                    .reason(reason)
                    .createdWatchingCount(createdWatching)
                    .triggeredCount(triggered)
                    .expiredCount(expired)
                    .ranAt(LocalDateTime.now())
                    .message(String.format("观察池新增 %d，买点触发 %d，失效 %d", createdWatching, triggered, expired))
                    .build();
        } finally {
            running.set(false);
        }
    }

    @Transactional
    int scanCandidatesInternal() {
        List<InvestStockPool> sourcePools = sourcePools();
        if (sourcePools.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (InvestStockPool pool : sourcePools) {
            if (signalRepository.existsByStockCodeAndSignalStatus(pool.getStockCode(), SIGNAL_STATUS_WATCHING)) {
                continue;
            }
            List<TradeStockDaily> recentDesc = dailyRepository.findTop30ByStockCodeOrderByTradeDateDesc(pool.getStockCode());
            LimitUpStreak streak = detectLatestStreak(pool.getStockCode(), pool.getStockName(), recentDesc);
            if (streak == null) {
                continue;
            }
            if (signalRepository.findByStockCodeAndFirstLimitUpDate(pool.getStockCode(), streak.firstLimitUpDate()).isPresent()) {
                continue;
            }
            InvestBigYangSignal signal = new InvestBigYangSignal();
            signal.setSourcePoolId(pool.getId());
            signal.setSourcePoolType(pool.getPoolType());
            signal.setStockCode(pool.getStockCode());
            signal.setStockName(displayName(pool));
            signal.setSignalStatus(SIGNAL_STATUS_WATCHING);
            signal.setLimitUpStreak(streak.streakDays());
            signal.setFirstLimitUpDate(streak.firstLimitUpDate());
            signal.setLastLimitUpDate(streak.lastLimitUpDate());
            signal.setBaseStartPrice(streak.baseStartPrice());
            signal.setFirstLimitUpOpenPrice(streak.firstLimitUpOpenPrice());
            signal.setFirstLimitUpClosePrice(streak.firstLimitUpClosePrice());
            signal.setLastLimitUpClosePrice(streak.lastLimitUpClosePrice());
            signal.setStatusReason("连续" + streak.streakDays() + "天涨停，等待回踩首板起涨点");
            signalRepository.save(signal);
            created++;
        }
        return created;
    }

    @Transactional
    TriggerResult scanTriggersInternal() {
        List<InvestBigYangSignal> watchingSignals = signalRepository.findTop200BySignalStatusOrderByUpdatedAtDescIdDesc(SIGNAL_STATUS_WATCHING);
        if (watchingSignals.isEmpty()) {
            return new TriggerResult(0, 0);
        }

        Map<Integer, InvestStockPool> sourcePoolMap = poolRepository.findAllById(
                        watchingSignals.stream()
                                .map(InvestBigYangSignal::getSourcePoolId)
                                .filter(id -> id != null && id > 0)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(InvestStockPool::getId, pool -> pool));
        Map<String, BigDecimal> realtimePriceMap = realtimePriceMap(watchingSignals.stream().map(InvestBigYangSignal::getStockCode).toList());
        Map<String, TradeStockDaily> latestDailyMap = latestDailyMap(watchingSignals.stream().map(InvestBigYangSignal::getStockCode).toList());

        int triggeredCount = 0;
        int expiredCount = 0;
        for (InvestBigYangSignal signal : watchingSignals) {
            InvestStockPool sourcePool = signal.getSourcePoolId() == null ? null : sourcePoolMap.get(signal.getSourcePoolId());
            if (sourcePool == null || !isSourcePool(sourcePool)) {
                signal.setSignalStatus(SIGNAL_STATUS_EXPIRED);
                signal.setStatusReason("源股票池条目已移除或已离场");
                signalRepository.save(signal);
                expiredCount++;
                continue;
            }

            BigDecimal currentPrice = currentPrice(signal.getStockCode(), realtimePriceMap, latestDailyMap);
            if (currentPrice == null || signal.getBaseStartPrice() == null || signal.getBaseStartPrice().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (isBrokenBelowBase(currentPrice, signal.getBaseStartPrice())) {
                signal.setSignalStatus(SIGNAL_STATUS_EXPIRED);
                signal.setStatusReason("价格跌破起涨点保护线");
                signalRepository.save(signal);
                expiredCount++;
                continue;
            }
            if (isExpiredByTradingDays(signal)) {
                signal.setSignalStatus(SIGNAL_STATUS_EXPIRED);
                signal.setStatusReason("超过观察期，未出现理想回踩");
                signalRepository.save(signal);
                expiredCount++;
                continue;
            }
            if (isWithinTriggerBand(currentPrice, signal.getBaseStartPrice())) {
                signal.setSignalStatus(SIGNAL_STATUS_TRIGGERED);
                signal.setTriggerPrice(scalePrice(currentPrice));
                signal.setTriggerDate(LocalDate.now());
                signal.setStatusReason("价格回踩到首板起涨点附近，触发买入提示");
                signalRepository.save(signal);
                saveAlert(signal, currentPrice);
                triggeredCount++;
            }
        }
        return new TriggerResult(triggeredCount, expiredCount);
    }

    LimitUpStreak detectLatestStreak(String stockCode, String stockName, List<TradeStockDaily> recentDesc) {
        if (recentDesc == null || recentDesc.size() < 2) {
            return null;
        }
        List<TradeStockDaily> recentAsc = new ArrayList<>(recentDesc);
        recentAsc.sort(Comparator.comparing(TradeStockDaily::getTradeDate));
        LocalDate latestDate = recentAsc.get(recentAsc.size() - 1).getTradeDate();
        for (int end = recentAsc.size() - 1; end >= 1; ) {
            TradeStockDaily current = recentAsc.get(end);
            TradeStockDaily prev = recentAsc.get(end - 1);
            if (!isLimitUp(current, prev, stockCode, stockName)) {
                end--;
                continue;
            }
            int start = end;
            while (start - 1 >= 1 && isLimitUp(recentAsc.get(start - 1), recentAsc.get(start - 2), stockCode, stockName)) {
                start--;
            }
            int streakDays = end - start + 1;
            if (streakDays >= properties.getMinStreakDays()
                    && streakDays <= properties.getMaxStreakDays()
                    && java.time.temporal.ChronoUnit.DAYS.between(recentAsc.get(end).getTradeDate(), latestDate) <= properties.getCandidateLookbackDays()) {
                TradeStockDaily first = recentAsc.get(start);
                BigDecimal baseStartPrice = baseStartPrice(first, prevCloseFor(first, recentAsc, start));
                return new LimitUpStreak(
                        streakDays,
                        first.getTradeDate(),
                        recentAsc.get(end).getTradeDate(),
                        scalePrice(baseStartPrice),
                        scalePrice(first.getOpenPrice()),
                        scalePrice(first.getClosePrice()),
                        scalePrice(recentAsc.get(end).getClosePrice())
                );
            }
            end = start - 1;
        }
        return null;
    }

    private BigDecimal prevCloseFor(TradeStockDaily first, List<TradeStockDaily> recentAsc, int startIndex) {
        if (startIndex <= 0) {
            return null;
        }
        return recentAsc.get(startIndex - 1).getClosePrice();
    }

    private BigDecimal baseStartPrice(TradeStockDaily first, BigDecimal prevClose) {
        if (first == null) {
            return null;
        }
        if (positive(first.getOpenPrice())) {
            return first.getOpenPrice();
        }
        if (positive(first.getLowPrice())) {
            return first.getLowPrice();
        }
        return prevClose;
    }

    private boolean isLimitUp(TradeStockDaily current, TradeStockDaily prev, String stockCode, String stockName) {
        if (current == null || prev == null || current.getClosePrice() == null || prev.getClosePrice() == null) {
            return false;
        }
        if (prev.getClosePrice().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal threshold = prev.getClosePrice()
                .multiply(BigDecimal.ONE.add(limitUpPct(stockCode, stockName).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
        BigDecimal pct = current.getClosePrice().subtract(prev.getClosePrice())
                .divide(prev.getClosePrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return current.getClosePrice().compareTo(threshold) >= 0
                || pct.compareTo(limitUpPct(stockCode, stockName)) >= 0;
    }

    private BigDecimal limitUpPct(String stockCode, String stockName) {
        if (stockName != null && stockName.toUpperCase().contains("ST")) {
            return BigDecimal.valueOf(4.8);
        }
        String code = stockCode == null ? "" : stockCode.toUpperCase();
        if (code.startsWith("300") || code.startsWith("301") || code.startsWith("688")) {
            return BigDecimal.valueOf(19.8);
        }
        if (code.startsWith("8") || code.startsWith("4")) {
            return BigDecimal.valueOf(29.8);
        }
        return BigDecimal.valueOf(9.8);
    }

    private boolean isWithinTriggerBand(BigDecimal currentPrice, BigDecimal basePrice) {
        if (!positive(currentPrice) || !positive(basePrice)) {
            return false;
        }
        BigDecimal upper = basePrice.multiply(BigDecimal.ONE.add(properties.getPullbackTolerancePct()
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
        return currentPrice.compareTo(upper) <= 0;
    }

    private boolean isBrokenBelowBase(BigDecimal currentPrice, BigDecimal basePrice) {
        if (!positive(currentPrice) || !positive(basePrice)) {
            return false;
        }
        BigDecimal lower = basePrice.multiply(BigDecimal.ONE.subtract(properties.getInvalidBreakPct()
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
        return currentPrice.compareTo(lower) < 0;
    }

    private boolean isExpiredByTradingDays(InvestBigYangSignal signal) {
        List<TradeStockDaily> records = dailyRepository.findByStockCodeAndTradeDateGreaterThanOrderByTradeDateAsc(
                signal.getStockCode(), signal.getLastLimitUpDate());
        return records.size() >= properties.getExpireTradingDays();
    }

    private void saveAlert(InvestBigYangSignal signal, BigDecimal currentPrice) {
        InvestAlert alert = new InvestAlert();
        alert.setStockCode(signal.getStockCode());
        alert.setSignalType(ALERT_SIGNAL_TYPE);
        alert.setLevel(signal.getLimitUpStreak() != null && signal.getLimitUpStreak() >= 2 ? 2 : 1);
        alert.setTitle(signal.getStockName() + " 大阳线回踩买点提示");
        alert.setContent(String.format("%s(%s) 连续%d天涨停后回踩到起涨点附近。首板日期 %s，起涨点 %.2f，当前价 %.2f。",
                signal.getStockName(),
                signal.getStockCode(),
                signal.getLimitUpStreak(),
                signal.getFirstLimitUpDate(),
                safeDecimal(signal.getBaseStartPrice()),
                safeDecimal(currentPrice)));
        alert.setTriggerPrice(scalePrice(currentPrice));
        alert.setTriggerAt(LocalDateTime.now());
        alert.setChannels("page");
        alert.setPushed(0);
        alert.setReadFlag(0);
        alertRepository.save(alert);
    }

    private BigYangSignalDTO toSignalDTO(InvestBigYangSignal signal, TradeStockDaily latestDaily) {
        BigDecimal currentPrice = latestDaily == null ? null : scalePrice(latestDaily.getClosePrice());
        BigDecimal distance = pctDistance(currentPrice, signal.getBaseStartPrice());
        return BigYangSignalDTO.builder()
                .id(signal.getId())
                .sourcePoolId(signal.getSourcePoolId())
                .sourcePoolType(signal.getSourcePoolType())
                .sourcePoolTypeLabel(poolTypeLabel(signal.getSourcePoolType()))
                .stockCode(signal.getStockCode())
                .stockName(signal.getStockName())
                .signalStatus(signal.getSignalStatus())
                .limitUpStreak(signal.getLimitUpStreak())
                .firstLimitUpDate(signal.getFirstLimitUpDate())
                .lastLimitUpDate(signal.getLastLimitUpDate())
                .baseStartPrice(signal.getBaseStartPrice())
                .firstLimitUpOpenPrice(signal.getFirstLimitUpOpenPrice())
                .firstLimitUpClosePrice(signal.getFirstLimitUpClosePrice())
                .lastLimitUpClosePrice(signal.getLastLimitUpClosePrice())
                .currentPrice(currentPrice)
                .currentPriceDate(latestDaily == null ? null : latestDaily.getTradeDate())
                .distanceToBasePct(distance)
                .triggerPrice(signal.getTriggerPrice())
                .triggerDate(signal.getTriggerDate())
                .statusReason(signal.getStatusReason())
                .build();
    }

    private BigYangAlertDTO toAlertDTO(InvestAlert alert, InvestBigYangSignal signal) {
        return BigYangAlertDTO.builder()
                .id(alert.getId())
                .stockCode(alert.getStockCode())
                .stockName(signal == null ? alert.getStockCode() : signal.getStockName())
                .title(alert.getTitle())
                .content(alert.getContent())
                .triggerPrice(alert.getTriggerPrice())
                .triggerAt(alert.getTriggerAt())
                .read(alert.getReadFlag() != null && alert.getReadFlag() == 1)
                .build();
    }

    private Map<String, TradeStockDaily> latestDailyMap(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Map.of();
        }
        return dailyRepository.findLatestByStockCodes(codes).stream()
                .collect(Collectors.toMap(TradeStockDaily::getStockCode, daily -> daily, (a, b) -> a));
    }

    private Map<String, BigDecimal> realtimePriceMap(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> result = new HashMap<>();
        for (TechAiQuoteSnapshot snapshot : eastMoneyRealtimeQuoteService.fetch(codes).values()) {
            if (snapshot.getLatestPrice() != null) {
                result.put(normalizeCode(snapshot.getStockCode()), scalePrice(snapshot.getLatestPrice()));
            }
        }
        List<String> missing = codes.stream()
                .filter(code -> !result.containsKey(normalizeCode(code)))
                .toList();
        if (!missing.isEmpty()) {
            for (TechAiQuoteSnapshot snapshot : sinaRealtimeQuoteService.fetch(missing).values()) {
                if (snapshot.getLatestPrice() != null) {
                    result.put(normalizeCode(snapshot.getStockCode()), scalePrice(snapshot.getLatestPrice()));
                }
            }
        }
        return result;
    }

    private BigDecimal currentPrice(String stockCode, Map<String, BigDecimal> realtimePriceMap, Map<String, TradeStockDaily> latestDailyMap) {
        BigDecimal realtime = realtimePriceMap.get(normalizeCode(stockCode));
        if (positive(realtime)) {
            return realtime;
        }
        TradeStockDaily latestDaily = latestDailyMap.get(stockCode);
        return latestDaily == null ? null : scalePrice(latestDaily.getClosePrice());
    }

    private List<InvestStockPool> sourcePools() {
        return poolRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(this::isSourcePool)
                .toList();
    }

    private boolean isSourcePool(InvestStockPool pool) {
        return pool != null
                && pool.getStockCode() != null
                && SOURCE_POOL_TYPES.contains(pool.getPoolType())
                && !"exited".equalsIgnoreCase(pool.getStatus());
    }

    private String displayName(InvestStockPool pool) {
        if (pool.getStockName() != null && !pool.getStockName().isBlank()) {
            return pool.getStockName();
        }
        return pool.getStockCode();
    }

    private String poolTypeLabel(String poolType) {
        if ("tech_vc".equals(poolType)) {
            return "科技风投";
        }
        if ("quality".equals(poolType)) {
            return "质量优选";
        }
        return poolType == null ? "" : poolType;
    }

    private int statusOrder(String status) {
        if (SIGNAL_STATUS_WATCHING.equals(status)) {
            return 0;
        }
        if (SIGNAL_STATUS_TRIGGERED.equals(status)) {
            return 1;
        }
        return 2;
    }

    private BigDecimal pctDistance(BigDecimal currentPrice, BigDecimal basePrice) {
        if (!positive(currentPrice) || !positive(basePrice)) {
            return null;
        }
        return currentPrice.subtract(basePrice)
                .divide(basePrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal scalePrice(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private double safeDecimal(BigDecimal value) {
        return value == null ? 0D : value.doubleValue();
    }

    private String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    record LimitUpStreak(
            int streakDays,
            LocalDate firstLimitUpDate,
            LocalDate lastLimitUpDate,
            BigDecimal baseStartPrice,
            BigDecimal firstLimitUpOpenPrice,
            BigDecimal firstLimitUpClosePrice,
            BigDecimal lastLimitUpClosePrice
    ) {
    }

    record TriggerResult(int triggeredCount, int expiredCount) {
    }
}
