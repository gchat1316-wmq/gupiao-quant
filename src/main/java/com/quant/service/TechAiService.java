package com.quant.service;

import com.quant.config.NotificationProperties;
import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.techai.TechAiAlertDTO;
import com.quant.dto.techai.TechAiPoolItemDTO;
import com.quant.entity.InvestAlert;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TechAiQuoteSnapshotRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.service.techai.TechAiAlertCandidate;
import com.quant.service.techai.TechAiAlertRuleEngine;
import com.quant.service.techai.TechAiMarketContext;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TechAiService {

    public static final String POOL_TYPE = "tech_ai";

    private final InvestStockPoolRepository poolRepository;
    private final TradeStockBasicRepository basicRepository;
    private final TradeStockDailyRepository dailyRepository;
    private final TechAiQuoteSnapshotRepository quoteRepository;
    private final InvestAlertRepository alertRepository;
    private final TechAiAlertRuleEngine ruleEngine;
    private final NotificationService notificationService;
    private final NotificationProperties notificationProperties;

    public TechAiService(InvestStockPoolRepository poolRepository,
                         TradeStockBasicRepository basicRepository,
                         TradeStockDailyRepository dailyRepository,
                         TechAiQuoteSnapshotRepository quoteRepository,
                         InvestAlertRepository alertRepository,
                         TechAiAlertRuleEngine ruleEngine,
                         NotificationService notificationService,
                         NotificationProperties notificationProperties) {
        this.poolRepository = poolRepository;
        this.basicRepository = basicRepository;
        this.dailyRepository = dailyRepository;
        this.quoteRepository = quoteRepository;
        this.alertRepository = alertRepository;
        this.ruleEngine = ruleEngine;
        this.notificationService = notificationService;
        this.notificationProperties = notificationProperties;
    }

    @Transactional(readOnly = true)
    public List<TechAiPoolItemDTO> listPool() {
        List<InvestStockPool> pool = poolRepository.findByPoolTypeOrderByCreatedAtDesc(POOL_TYPE);
        if (pool.isEmpty()) {
            return List.of();
        }
        List<String> codes = pool.stream().map(InvestStockPool::getStockCode).toList();
        Map<String, TechAiQuoteSnapshot> quotes = latestQuotes(codes);
        Map<String, TradeStockBasic> basics = basics(codes);
        return pool.stream()
                .map(item -> toPoolDTO(item, basicFromMap(basics, item.getStockCode()), quotes.get(item.getStockCode())))
                .toList();
    }

    @Transactional
    public TechAiPoolItemDTO addToPool(PoolSaveRequest request) {
        String keyword = request.getKeyword() == null ? "" : request.getKeyword().trim();
        if (keyword.isBlank()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        String stockCode = resolveStockCode(keyword);
        Optional<InvestStockPool> existing = poolRepository.findByStockCode(stockCode);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("该股票已在股票池中：" + stockCode);
        }

        InvestStockPool pool = new InvestStockPool();
        pool.setStockCode(stockCode);
        TradeStockBasic basic = basic(stockCode);
        if (basic != null) {
            pool.setStockName(basic.getStockName());
        }
        pool.setPoolType(POOL_TYPE);
        pool.setStatus(request.getStatus() == null || request.getStatus().isBlank() ? "watching" : request.getStatus());
        pool.setMemo(request.getMemo());
        InvestStockPool saved = poolRepository.save(pool);
        return toPoolDTO(saved, basic, quoteRepository.findFirstByStockCodeOrderByQuoteTimeDesc(saved.getStockCode()).orElse(null));
    }

    @Transactional
    public TechAiPoolItemDTO updateField(Integer id, PoolFieldUpdateRequest request) {
        InvestStockPool pool = poolRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("股票池条目不存在：" + id));
        ensureTechAi(pool);
        String field = request.getField() == null ? "" : request.getField().trim();
        String value = request.getValue();
        boolean blank = value == null || value.isBlank();
        switch (field) {
            case "status" -> pool.setStatus(blank ? "watching" : value.trim());
            case "memo" -> pool.setMemo(blank ? null : value);
            default -> throw new IllegalArgumentException("不支持的字段：" + field);
        }
        InvestStockPool saved = poolRepository.save(pool);
        return toPoolDTO(saved, basic(saved.getStockCode()), quoteRepository.findFirstByStockCodeOrderByQuoteTimeDesc(saved.getStockCode()).orElse(null));
    }

    @Transactional
    public void removeFromPool(Integer id) {
        InvestStockPool pool = poolRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("股票池条目不存在：" + id));
        ensureTechAi(pool);
        poolRepository.delete(pool);
    }

    @Transactional(readOnly = true)
    public List<TechAiAlertDTO> listAlerts() {
        List<String> codes = poolRepository.findByPoolTypeOrderByCreatedAtDesc(POOL_TYPE).stream()
                .map(InvestStockPool::getStockCode)
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
        List<InvestStockPool> pool = poolRepository.findByPoolTypeAndStatusNotOrderByCreatedAtDesc(POOL_TYPE, "exited");
        if (pool.isEmpty()) {
            return 0;
        }
        List<String> codes = pool.stream().map(InvestStockPool::getStockCode).toList();
        Map<String, TechAiQuoteSnapshot> quotes = latestQuotes(codes);
        Map<String, TradeStockBasic> basics = basics(codes);
        int triggered = 0;
        for (InvestStockPool item : pool) {
            TechAiQuoteSnapshot quote = quotes.get(item.getStockCode());
            if (quote == null) {
                continue;
            }
            String stockName = displayStockName(item, basicFromMap(basics, item.getStockCode()));
            TechAiMarketContext ctx = buildContext(item.getStockCode(), stockName, quote);
            for (TechAiAlertCandidate candidate : ruleEngine.evaluate(ctx)) {
                if (shouldPush(candidate, cfg)) {
                    saveAndPush(candidate, quote);
                    triggered++;
                }
            }
        }
        if (triggered > 0) {
            log.info("科技AI行情监控触发 {} 条告警", triggered);
        }
        return triggered;
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

    private TechAiPoolItemDTO toPoolDTO(InvestStockPool item, TradeStockBasic basic, TechAiQuoteSnapshot quote) {
        BigDecimal dailyChange = quote == null ? null : pctChange(quote.getLatestPrice(), quote.getPrevClosePrice());
        return TechAiPoolItemDTO.builder()
                .id(item.getId())
                .stockCode(item.getStockCode())
                .qmtCode(TechAiStockCodeUtils.toQmtCode(item.getStockCode()))
                .stockName(displayStockName(item, basic))
                .status(item.getStatus())
                .memo(item.getMemo())
                .latestPrice(quote == null ? null : quote.getLatestPrice())
                .dailyChangePct(dailyChange)
                .turnoverRate(quote == null ? null : quote.getTurnoverRate())
                .volume(quote == null ? null : quote.getVolume())
                .quoteTime(quote == null ? null : quote.getQuoteTime())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }

    private String displayStockName(InvestStockPool item, TradeStockBasic basic) {
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

    private void ensureTechAi(InvestStockPool pool) {
        if (!POOL_TYPE.equals(pool.getPoolType())) {
            throw new IllegalArgumentException("只能操作科技AI股票池条目");
        }
    }

    private boolean isTradingTime() {
        LocalTime now = LocalTime.now();
        return (now.isAfter(LocalTime.of(9, 29)) && now.isBefore(LocalTime.of(11, 31)))
                || (now.isAfter(LocalTime.of(12, 59)) && now.isBefore(LocalTime.of(15, 1)));
    }
}
