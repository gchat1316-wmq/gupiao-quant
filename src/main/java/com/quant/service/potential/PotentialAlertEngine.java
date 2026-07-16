package com.quant.service.potential;

import com.quant.config.NotificationProperties;
import com.quant.entity.InvestAlert;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.PotentialPool;
import com.quant.entity.TechAiQuoteSnapshot;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.service.NotificationService;
import com.quant.service.techai.TechAiAlertCandidate;
import com.quant.service.techai.TechAiAlertRuleEngine;
import com.quant.service.techai.TechAiAlertThresholds;
import com.quant.service.techai.TechAiMarketContext;
import com.quant.service.techai.TechAiPositionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 潜力监控 · 告警与持仓信号评估。
 *
 * <p>包含两类定时作业与告警推送：
 *
 * <ul>
 *   <li>{@link #monitorQuotes(NotificationProperties.QuoteMonitor, List, Map, Map)}：盘中扫盘（% 阈值告警 + 持仓信号 + MonitorService 融合扫描）
 *   <li>{@link #confirmPositionSignals(NotificationProperties.QuoteMonitor, List)}：收盘确认（按 a-stock-data 实时价定位）
 *   <li>{@link #evaluateIntradayPosition}：单标的盘中持仓信号
 *   <li>{@link #pushPositionSignal}：构造并推送持仓信号告警
 *   <li>{@link #saveAndPush}：保存+推送纯阈值告警
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PotentialAlertEngine {

  private final InvestPositionCommonRepository positionRepository;
  private final InvestAlertRepository alertRepository;
  private final TradeStockDailyRepository dailyRepository;
  private final TechAiAlertRuleEngine ruleEngine;
  private final TechAiPositionEngine positionEngine;
  private final NotificationService notificationService;
  private final PotentialQuoteAggregator quoteAggregator;
  private final PotentialPositionCalculator positionCalculator;

  /**
   * 盘中扫盘：对每个 potential pool 评估阈值告警与持仓信号。
   *
   * @param cfg  QuoteMonitor 配置（开关 / 冷却 / 交易日去重 / 交易时段）
   * @param pool 当前活跃 pool（status != "exited"）
   * @param quotes 已聚合的最新行情快照 map（key 为归一化 code）
   * @param basics 已聚合的基础信息 map
   * @return 触发的告警数（含阈值告警 + 持仓信号）
   */
  public int monitorQuotes(NotificationProperties.QuoteMonitor cfg,
                           List<PotentialPool> pool,
                           Map<String, TechAiQuoteSnapshot> quotes,
                           Map<String, TradeStockBasic> basics) {
    int triggered = 0;
    for (PotentialPool item : pool) {
      TechAiQuoteSnapshot quote = quotes.get(item.getStockCode());
      if (quote == null) {
        continue;
      }
      InvestPositionCommon position = positionRepository
          .findByStockCodeAndPoolType(item.getStockCode(), PotentialPositionCalculator.POOL_TYPE_POTENTIAL).orElse(null);
      String stockName = PotentialPoolSupport.displayStockName(item, quoteAggregator.basicFromMap(basics, item.getStockCode()));
      TechAiMarketContext ctx = buildContext(item.getStockCode(), stockName, quote);
      for (TechAiAlertCandidate candidate : ruleEngine.evaluate(ctx, thresholds(position))) {
        if (shouldPush(candidate, cfg)) {
          saveAndPush(candidate, quote);
          triggered++;
        }
      }
      triggered += evaluateIntradayPosition(item, position, quote, cfg);
    }
    return triggered;
  }

  /** 收盘确认：按 a-stock-data 实时收盘价定位持仓信号并推送（两段式中的确认段）。 */
  public int confirmPositionSignals(NotificationProperties.QuoteMonitor cfg,
                                    List<PotentialPool> pool,
                                    Map<String, com.quant.service.AStockDataQuoteService.QuoteSnapshot> quoteMap) {
    int triggered = 0;
    for (PotentialPool item : pool) {
      InvestPositionCommon position = positionRepository
          .findByStockCodeAndPoolType(item.getStockCode(), PotentialPositionCalculator.POOL_TYPE_POTENTIAL).orElse(null);
      if (position == null || position.getPositionLots() == null
          || position.getPositionLots().compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      com.quant.service.AStockDataQuoteService.QuoteSnapshot snapshot = quoteMap.get(
          item.getStockCode() == null ? "" : item.getStockCode().trim().toUpperCase(Locale.ROOT));
      if (snapshot == null || snapshot.latestPrice() == null || snapshot.latestPrice().compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      BigDecimal close = snapshot.latestPrice();
      // 历史 K 线仍来自 trade_stock_daily（用于峰值参考与 ATR）
      List<TradeStockDaily> recentKline = dailyRepository.findTop6ByStockCodeOrderByTradeDateDesc(item.getStockCode());
      BigDecimal historicalHigh = recentKline.isEmpty() ? null : recentKline.get(0).getHighPrice();
      BigDecimal atr = positionCalculator.isAtrMode(position)
          ? positionCalculator.atrFor(position, item.getStockCode()) : null;
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
    return triggered;
  }

  int evaluateIntradayPosition(PotentialPool item, InvestPositionCommon position,
                               TechAiQuoteSnapshot quote, NotificationProperties.QuoteMonitor cfg) {
    if (position == null || position.getPositionLots() == null
        || position.getPositionLots().compareTo(BigDecimal.ZERO) <= 0) {
      return 0;
    }
    BigDecimal price = quote.getLatestPrice();
    if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
      return 0;
    }
    BigDecimal atr = positionCalculator.isAtrMode(position)
        ? positionCalculator.atrFor(position, item.getStockCode()) : null;
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

  boolean pushPositionSignal(PotentialPool item, InvestPositionCommon position, BigDecimal price,
                             TechAiPositionEngine.PositionPlan plan, boolean confirm,
                             NotificationProperties.QuoteMonitor cfg) {
    String signal = plan.getPendingSignal();
    String signalType = "position_" + signal.toLowerCase() + (confirm ? "_confirm" : "_warn");
    if (!shouldPushPosition(item.getStockCode(), signalType, confirm, cfg)) {
      return false;
    }
    String stockName = PotentialPoolSupport.displayStockName(item, quoteAggregator.basic(item.getStockCode()));
    String phase = confirm ? "收盘确认" : "盘中预警";
    String actionLabel = switch (signal) {
      case TechAiPositionEngine.SIGNAL_STOP -> "清仓信号";
      case TechAiPositionEngine.SIGNAL_ADD -> "加仓信号";
      case TechAiPositionEngine.SIGNAL_TP -> "止盈信号";
      default -> "持仓信号";
    };
    String title = String.format("【%s·%s】%s(%s) @ %s",
        actionLabel, phase, stockName, item.getStockCode(), PotentialPoolSupport.fmt(price));
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

  boolean shouldPushPosition(String stockCode, String signalType, boolean confirm,
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

  int positionLevel(String signal) {
    return switch (signal) {
      case TechAiPositionEngine.SIGNAL_STOP -> 3;
      case TechAiPositionEngine.SIGNAL_ADD, TechAiPositionEngine.SIGNAL_TP -> 2;
      default -> 1;
    };
  }

  String buildPositionContent(PotentialPool item, InvestPositionCommon position, String stockName, BigDecimal price,
                              TechAiPositionEngine.PositionPlan plan, String signal, String phase) {
    String advice = switch (signal) {
      case TechAiPositionEngine.SIGNAL_STOP -> "现价已触及移动止损，建议清仓离场。";
      case TechAiPositionEngine.SIGNAL_ADD -> String.format("现价突破加仓位，建议加仓 %s 手。",
          plan.getNextAddLots() == null ? "-" : PotentialPoolSupport.fmt(plan.getNextAddLots()));
      case TechAiPositionEngine.SIGNAL_TP -> String.format("现价达到目标价，建议减仓 %s%% 止盈。",
          position == null || position.getTakeProfitPct() == null ? "50" : PotentialPoolSupport.fmt(position.getTakeProfitPct()));
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
        PotentialPoolSupport.fmt(price),
        PotentialPoolSupport.fmt(position != null ? position.getAvgCost() : null),
        PotentialPoolSupport.fmt(position != null ? position.getPositionLots() : null),
        PotentialPoolSupport.fmt(plan.getStopPrice()),
        PotentialPoolSupport.fmt(plan.getNextAddPrice()),
        PotentialPoolSupport.fmt(plan.getTargetPrice()),
        PotentialPoolSupport.fmt(plan.getFloatingPnl()),
        PotentialPoolSupport.fmt(plan.getFloatingPnlPct()),
        warn);
  }

  TechAiAlertThresholds thresholds(InvestPositionCommon pos) {
    return TechAiAlertThresholds.builder()
        .minute1Pct(pos != null ? pos.getAlertMinute1mPct() : null)
        .minute5Pct(pos != null ? pos.getAlertMinute5mPct() : null)
        .dailyPct(pos != null ? pos.getAlertDailyPct() : null)
        .threeDayPct(pos != null ? pos.getAlertThreeDayPct() : null)
        .turnoverRatioPct(pos != null ? pos.getAlertTurnoverRatioPct() : null)
        .build();
  }

  TechAiMarketContext buildContext(String stockCode, String stockName, TechAiQuoteSnapshot quote) {
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

  boolean shouldPush(TechAiAlertCandidate candidate, NotificationProperties.QuoteMonitor cfg) {
    String signalType = candidate.ruleType() + ":" + candidate.threshold().stripTrailingZeros().toPlainString();
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
        candidate.stockCode(), signalType, today.atStartOfDay(), today.plusDays(1).atStartOfDay().minusNanos(1));
  }

  void saveAndPush(TechAiAlertCandidate candidate, TechAiQuoteSnapshot quote) {
    InvestAlert alert = new InvestAlert();
    alert.setStockCode(candidate.stockCode());
    alert.setSignalType(candidate.ruleType() + ":" + candidate.threshold().stripTrailingZeros().toPlainString());
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

  BigDecimal averageTurnover(List<TradeStockDaily> records) {
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
}