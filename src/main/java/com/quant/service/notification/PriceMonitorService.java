package com.quant.service.notification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.config.NotificationProperties;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceMonitorService {

  private static final String POOL_TYPE_INVEST = "invest";
  private static final String STATE_NONE = "none";
  private static final String STATE_BUY = "buy_alerted";
  private static final String STATE_SELL = "sell_alerted";
  public static final String TYPE_BUY = "PRICE_BUY_ALERT";
  public static final String TYPE_SELL = "PRICE_SELL_ALERT";

  private final InvestStockPoolRepository poolRepository;
  private final InvestPositionCommonRepository positionRepository;
  private final TradeStockBasicRepository basicRepository;
  private final AStockDataQuoteService aStockDataQuoteService;
  private final NotificationProperties notifProps;
  private final NotificationDispatcher notificationDispatcher;

  @Scheduled(cron = "${notification.price-monitor.cron:0 */5 9-15 * * MON-FRI}")
  @Transactional
  public void monitorPrices() {
    NotificationProperties.PriceMonitor cfg = notifProps.getPriceMonitor();
    if (!cfg.isEnabled()) return;

    if (cfg.isRequireTradingTime() && !isTradingTime()) {
      log.debug("当前不在交易时间内，跳过价格监控");
      return;
    }

    Map<String, InvestPositionCommon> posMap =
        positionRepository.findByPoolType(POOL_TYPE_INVEST).stream()
            .filter(p -> !"exited".equalsIgnoreCase(p.getStatus()))
            .collect(Collectors.toMap(InvestPositionCommon::getStockCode, p -> p, (a, b) -> a));

    if (posMap.isEmpty()) {
      log.debug("股票池中无需要监控的标的");
      return;
    }

    List<String> codes = posMap.keySet().stream().toList();
    Map<String, BigDecimal> latestPriceMap =
        aStockDataQuoteService.fetchQuotes(codes).values().stream()
            .filter(snapshot -> snapshot.latestPrice() != null)
            .collect(
                Collectors.toMap(
                    snapshot ->
                        snapshot.stockCode() == null
                            ? ""
                            : snapshot.stockCode().toUpperCase(java.util.Locale.ROOT),
                    snapshot -> snapshot.latestPrice(),
                    (a, b) -> a));
    Map<String, String> nameMap =
        basicRepository.findByStockCodeIn(codes).stream()
            .collect(
                Collectors.toMap(
                    TradeStockBasic::getStockCode, TradeStockBasic::getStockName, (a, b) -> a));
    Map<String, InvestStockPool> poolMap =
        poolRepository.findByStockCodeIn(codes).stream()
            .collect(Collectors.toMap(InvestStockPool::getStockCode, p -> p, (a, b) -> a));

    int triggered = 0;
    LocalDateTime now = LocalDateTime.now();

    for (Map.Entry<String, InvestPositionCommon> entry : posMap.entrySet()) {
      String code = entry.getKey();
      InvestPositionCommon position = entry.getValue();
      InvestStockPool pool = poolMap.get(code);
      if (pool == null) continue;

      BigDecimal close = latestPriceMap.get(code);
      if (close == null) continue;

      if ((pool.getTargetBuyPrice() == null && position.getTargetSellPrice() == null)) continue;

      String currentState =
          position.getAlertState() == null ? STATE_NONE : position.getAlertState();
      String desiredState = STATE_NONE;

      if (pool.getTargetBuyPrice() != null && close.compareTo(pool.getTargetBuyPrice()) <= 0) {
        desiredState = STATE_BUY;
      } else if (position.getTargetSellPrice() != null
          && close.compareTo(position.getTargetSellPrice()) >= 0) {
        desiredState = STATE_SELL;
      }

      if (desiredState.equals(currentState)) {
        continue;
      }

      if (!STATE_NONE.equals(desiredState)
          && position.getLastAlertAt() != null
          && position.getLastAlertAt().plusMinutes(cfg.getCooldownMinutes()).isAfter(now)) {
        log.debug("[{}] {} 状态需要切换到 {} 但仍在冷却期内", code, currentState, desiredState);
        continue;
      }

      String stockName = nameMap.getOrDefault(code, code);
      position.setAlertState(desiredState);

      if (STATE_NONE.equals(desiredState)) {
        positionRepository.save(position);
        log.info("[{}] {} 价格 {} 已回到正常区间，重置提醒状态", code, stockName, close);
        continue;
      }

      String title;
      String desp;
      String alertType;
      if (STATE_BUY.equals(desiredState)) {
        title = String.format("📉 %s(%s) 触及买入价 %s", stockName, code, pool.getTargetBuyPrice());
        desp = buildAlertContent(stockName, pool, position, close, true);
        alertType = TYPE_BUY;
      } else {
        title = String.format("📈 %s(%s) 触及卖出价 %s", stockName, code, position.getTargetSellPrice());
        desp = buildAlertContent(stockName, pool, position, close, false);
        alertType = TYPE_SELL;
      }

      // 按用户偏好 fanout 到 SMS / WECHAT，每个目标写一条 user_notification_log
      NotificationDispatcher.DispatchResult result =
          notificationDispatcher.dispatchPriceAlert(code, alertType, title, desp);

      if (result.succeeded() > 0) {
        position.setLastAlertAt(now);
        triggered++;
      }
      positionRepository.save(position);
    }

    if (triggered > 0) {
      log.info("价格监控本次触发 {} 条推送（共 {} 只标的）", triggered, posMap.size());
    }
    // fixed_* fusion 已由 MonitorService 主 cron（poolTypes 含 invest）覆盖，这里不再重复 scan，避免双推。
  }

  private String buildAlertContent(
      String stockName,
      InvestStockPool pool,
      InvestPositionCommon position,
      BigDecimal close,
      boolean isBuy) {
    StringBuilder sb = new StringBuilder();
    sb.append("## ").append(stockName).append("（").append(pool.getStockCode()).append("）\n\n");
    sb.append("**当前价**：").append(close).append("\n\n");
    if (isBuy) {
      sb.append("**目标买入价**：").append(pool.getTargetBuyPrice()).append(" ✅ 已触发\n\n");
      if (position.getTargetSellPrice() != null) {
        sb.append("**目标卖出价**：").append(position.getTargetSellPrice()).append("\n\n");
      }
    } else {
      sb.append("**目标卖出价**：").append(position.getTargetSellPrice()).append(" ✅ 已触发\n\n");
      if (pool.getTargetBuyPrice() != null) {
        sb.append("**目标买入价**：").append(pool.getTargetBuyPrice()).append("\n\n");
      }
    }
    if (pool.getUndervaluedPrice() != null
        || pool.getFairPrice() != null
        || pool.getOvervaluedPrice() != null) {
      sb.append("**估值锚点**：低估 ")
          .append(safe(pool.getUndervaluedPrice()))
          .append(" / 合理 ")
          .append(safe(pool.getFairPrice()))
          .append(" / 高估 ")
          .append(safe(pool.getOvervaluedPrice()))
          .append("\n\n");
    }
    if (pool.getMemo() != null && !pool.getMemo().isBlank()) {
      sb.append("**备注**：").append(pool.getMemo()).append("\n\n");
    }
    sb.append("---\n").append("由龙江投资股票池价格监控自动推送 · ").append(LocalDateTime.now());
    return sb.toString();
  }

  private String safe(BigDecimal v) {
    return v == null ? "—" : v.toPlainString();
  }

  private boolean isTradingTime() {
    LocalTime now = LocalTime.now();
    return (now.isAfter(LocalTime.of(9, 29)) && now.isBefore(LocalTime.of(11, 31)))
        || (now.isAfter(LocalTime.of(12, 59)) && now.isBefore(LocalTime.of(15, 1)));
  }
}
