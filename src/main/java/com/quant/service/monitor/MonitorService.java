package com.quant.service.monitor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.config.NotificationProperties;
import com.quant.entity.InvestAlert;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;
import com.quant.service.notification.NotificationService;
import com.quant.service.techai.TechAiAtrCalculator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一监控调度器 — 把固定价/涨跌幅/ATR/止盈止损所有触发收口到一处。
 *
 * <p>入站： - 每分钟 cron {@code notification.monitor.cron} 触发 {@link #scanAll()} - 收盘 15:05 cron {@code
 * notification.monitor.confirm-cron} 触发 {@link #confirm()} - 旧 TechAiService / PotentialService /
 * PriceMonitorService 调用 {@link #scan(String)} 入站
 *
 * <p>出站： - InvestAlert 行 (持久化到 invest_alert 表) - Server酱 Markdown 推送 (走 NotificationService)
 *
 * <p>每个 (stockCode, signalType) 在 {@code notification.monitor.cooldown-minutes} 内只推送一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

  /** 投资池在 invest_position_common 中的正式 poolType；历史 UI 曾用 "stock" 作为别名。 */
  public static final String POOL_INVEST = "invest";

  public static final String POOL_TECH_AI = "tech_ai";
  public static final String POOL_POTENTIAL = "potential";

  private final InvestPositionCommonRepository posRepo;
  private final InvestAlertRepository alertRepo;
  private final TradeStockDailyRepository dailyRepo;
  private final TradeStockBasicRepository basicRepo;
  private final AStockDataQuoteService quoteService;
  private final NotificationService notificationService;
  private final MonitorRuleEngine ruleEngine;
  private final TechAiAtrCalculator atrCalculator;
  private final NotificationProperties notifProps;

  /** Master every-minute cron — iterates all configured pool types. */
  @Scheduled(cron = "${notification.monitor.cron:0 */1 9-15 * * MON-FRI}")
  public void scanAll() {
    NotificationProperties.Monitor cfg = notifProps.getMonitor();
    if (cfg == null || !cfg.isEnabled()) return;
    for (String poolType : cfg.getPoolTypes()) {
      try {
        scan(normalizePoolType(poolType));
      } catch (Exception e) {
        log.error("[{}] scanAll 中 scan 异常", poolType, e);
      }
    }
  }

  /** Close-of-day confirm at 15:05 — same evaluation logic, gives close-price a final shot. */
  @Scheduled(cron = "${notification.monitor.confirm-cron:0 5 15 * * MON-FRI}")
  public void confirm() {
    NotificationProperties.Monitor cfg = notifProps.getMonitor();
    if (cfg == null || !cfg.isEnabled()) return;
    for (String poolType : cfg.getPoolTypes()) {
      try {
        scan(normalizePoolType(poolType));
      } catch (Exception e) {
        log.error("[{}] confirm 中 scan 异常", poolType, e);
      }
    }
  }

  /**
   * 单 pool 扫描入口 — TechAi/Potential/PriceMonitor 服务都通过这里。
   *
   * @return 本次命中的信号条数 (不计 cooldown 抑制的条数)
   */
  @Transactional
  public int scan(String poolType) {
    NotificationProperties.Monitor cfg = notifProps.getMonitor();
    if (cfg == null || !cfg.isEnabled()) return 0;
    if (cfg.isRequireTradingTime() && !isTradingTime()) return 0;

    String normalized = normalizePoolType(poolType);
    List<InvestPositionCommon> positions = posRepo.findByPoolType(normalized);
    if (positions == null || positions.isEmpty()) return 0;

    List<InvestPositionCommon> active =
        positions.stream().filter(p -> !isExited(p)).toList();
    if (active.isEmpty()) return 0;

    List<String> codes = active.stream().map(InvestPositionCommon::getStockCode).toList();

    Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap;
    try {
      quoteMap = quoteService.fetchQuotes(codes);
    } catch (Exception e) {
      log.warn("[{}] 拉取行情失败，跳过本次扫描", normalized, e);
      return 0;
    }

    Map<String, String> nameMap =
        basicRepo.findByStockCodeIn(codes).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    TradeStockBasic::getStockCode,
                    TradeStockBasic::getStockName,
                    (a, b) -> a));

    int triggered = 0;
    for (InvestPositionCommon pos : active) {
      try {
        String name = nameMap.getOrDefault(pos.getStockCode(), pos.getStockCode());
        if (scanOne(pos, name, quoteMap.get(pos.getStockCode()), cfg)) triggered++;
      } catch (Exception e) {
        log.warn("[{}] scan 异常: {}", pos.getStockCode(), e.getMessage());
      }
    }
    if (triggered > 0) {
      log.info("[{}] monitor scan 本次触发 {} 条推送（共 {} 只标的）", normalized, triggered, active.size());
    }
    return triggered;
  }

  /** 单条评估 + 推送 + 持久化。返回是否命中并推送。 */
  private boolean scanOne(
      InvestPositionCommon pos,
      String stockName,
      AStockDataQuoteService.QuoteSnapshot quote,
      NotificationProperties.Monitor cfg) {
    if (quote == null || quote.latestPrice() == null) return false;

    // 刷新峰值（用于 ATR 移动止损）
    BigDecimal latest = quote.latestPrice();
    if (pos.getPeakPrice() == null || latest.compareTo(pos.getPeakPrice()) > 0) {
      pos.setPeakPrice(latest);
      posRepo.save(pos);
    }

    BigDecimal atr = computeAtr(pos);
    BigDecimal openToday =
        quote.openPrice() != null
            ? quote.openPrice()
            : quote.prevClosePrice(); // 无今开时退回昨收，避免振幅恒为 0
    BigDecimal close3d = closePriceNTradingDaysAgo(pos.getStockCode(), 3);

    MonitorContext ctx =
        MonitorContext.builder()
            .position(pos)
            .stockCode(pos.getStockCode())
            .stockName(stockName)
            .latest(latest)
            .openToday(openToday)
            .prevClose(quote.prevClosePrice())
            .closePrice3DaysAgo(close3d)
            .atr(atr)
            .quoteTime(quote.quoteTime())
            .build();

    List<MonitorSignal> signals = ruleEngine.evaluate(ctx);
    if (signals.isEmpty()) return false;

    boolean any = false;
    for (MonitorSignal sig : signals) {
      if (pushIfCool(pos, sig, cfg)) any = true;
    }
    return any;
  }

  /**
   * 推送到 Server酱 + 写入 InvestAlert。 Cooldown: 同 (stockCode, signalType) 在 cfg.cooldownMinutes 内跳过 —
   * 复用 invest_alert 表查最近一条。 Server酱失败不抛异常 — 仅记日志并把 InvestAlert.pushed 置 0。
   */
  private boolean pushIfCool(
      InvestPositionCommon pos, MonitorSignal sig, NotificationProperties.Monitor cfg) {
    Optional<InvestAlert> last;
    try {
      last =
          alertRepo.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(
              pos.getStockCode(), sig.getSignalType());
    } catch (Exception e) {
      log.debug("[{}] 历史告警查询失败，按无冷却处理: {}", pos.getStockCode(), e.getMessage());
      last = Optional.empty();
    }
    if (last.isPresent()
        && last.get().getTriggerAt() != null
        && last.get()
            .getTriggerAt()
            .plusMinutes(cfg.getCooldownMinutes())
            .isAfter(LocalDateTime.now())) {
      log.debug("[{}] {} 在冷却期内，跳过推送", pos.getStockCode(), sig.getSignalType());
      return false;
    }

    InvestAlert alert = new InvestAlert();
    alert.setStockCode(pos.getStockCode());
    alert.setSignalType(sig.getSignalType());
    alert.setLevel(2);
    alert.setTitle(sig.getTitle());
    alert.setContent(MonitorAlertTemplate.render(sig));
    alert.setTriggerPrice(sig.getTriggerPrice());
    alert.setTriggerAt(sig.getTriggeredAt() != null ? sig.getTriggeredAt() : LocalDateTime.now());
    alert.setChannels("serverchan");
    alert.setPushed(0);

    boolean sent = false;
    try {
      sent = notificationService.sendServerChan(sig.getTitle(), alert.getContent());
      alert.setPushed(sent ? 1 : 0);
    } catch (Exception e) {
      log.warn(
          "[{}] Server酱推送失败 (signal={}): {}",
          pos.getStockCode(),
          sig.getSignalType(),
          e.getMessage());
    }
    try {
      alertRepo.save(alert);
    } catch (Exception e) {
      log.warn("[{}] InvestAlert 持久化失败: {}", pos.getStockCode(), e.getMessage());
    }

    // 无论推送是否成功，更新 lastAlertAt 便于前端展示
    try {
      pos.setLastAlertAt(alert.getTriggerAt());
      posRepo.save(pos);
    } catch (Exception e) {
      log.debug("[{}] 更新 lastAlertAt 失败: {}", pos.getStockCode(), e.getMessage());
    }
    return sent;
  }

  private BigDecimal computeAtr(InvestPositionCommon pos) {
    Integer period = pos.getAtrPeriod();
    if (period == null || period <= 0) {
      // ATR 振幅告警启用但未设周期时给默认 14
      if (intAsBool(pos.getAtrAlertEnabled()) || Integer.valueOf(1).equals(pos.getUseAtr())) {
        period = 14;
      } else {
        return null;
      }
    }
    try {
      List<TradeStockDaily> dailies =
          dailyRepo.findTop30ByStockCodeOrderByTradeDateDesc(pos.getStockCode());
      return atrCalculator.atr(dailies, period);
    } catch (Exception e) {
      return null;
    }
  }

  private BigDecimal closePriceNTradingDaysAgo(String stockCode, int n) {
    try {
      List<TradeStockDaily> dailies = dailyRepo.findTop6ByStockCodeOrderByTradeDateDesc(stockCode);
      if (dailies == null || dailies.size() <= n) return null;
      TradeStockDaily row = dailies.get(n);
      return row == null ? null : row.getClosePrice();
    } catch (Exception e) {
      return null;
    }
  }

  private boolean isTradingTime() {
    LocalTime now = LocalTime.now();
    return (now.isAfter(LocalTime.of(9, 29)) && now.isBefore(LocalTime.of(11, 31)))
        || (now.isAfter(LocalTime.of(12, 59)) && now.isBefore(LocalTime.of(15, 1)));
  }

  private static boolean isExited(InvestPositionCommon p) {
    return p != null && p.getStatus() != null && "exited".equalsIgnoreCase(p.getStatus());
  }

  private static boolean intAsBool(Integer i) {
    return i != null && i == 1;
  }

  /** 兼容历史别名 stock → invest。 */
  public static String normalizePoolType(String poolType) {
    if (poolType == null || poolType.isBlank()) return poolType;
    String p = poolType.trim().toLowerCase(Locale.ROOT);
    if ("stock".equals(p)) return POOL_INVEST;
    return p;
  }

  public static boolean isKnownPoolType(String poolType) {
    String p = normalizePoolType(poolType);
    return POOL_INVEST.equals(p) || POOL_TECH_AI.equals(p) || POOL_POTENTIAL.equals(p);
  }
}
