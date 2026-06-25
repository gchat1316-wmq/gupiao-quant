package com.quant.service;

import com.quant.config.NotificationProperties;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PriceMonitorService {

    private static final String STATE_NONE = "none";
    private static final String STATE_BUY  = "buy_alerted";
    private static final String STATE_SELL = "sell_alerted";

    private final InvestStockPoolRepository poolRepository;
    private final TradeStockBasicRepository basicRepository;
    private final AStockDataQuoteService aStockDataQuoteService;
    private final NotificationService notificationService;
    private final NotificationProperties notifProps;

    public PriceMonitorService(InvestStockPoolRepository poolRepository,
                               TradeStockBasicRepository basicRepository,
                               AStockDataQuoteService aStockDataQuoteService,
                               NotificationService notificationService,
                               NotificationProperties notifProps) {
        this.poolRepository = poolRepository;
        this.basicRepository = basicRepository;
        this.aStockDataQuoteService = aStockDataQuoteService;
        this.notificationService = notificationService;
        this.notifProps = notifProps;
    }

    @Scheduled(cron = "${notification.price-monitor.cron:0 */5 9-15 * * MON-FRI}")
    @Transactional
    public void monitorPrices() {
        NotificationProperties.PriceMonitor cfg = notifProps.getPriceMonitor();
        if (!cfg.isEnabled()) return;

        if (cfg.isRequireTradingTime() && !isTradingTime()) {
            log.debug("当前不在交易时间内，跳过价格监控");
            return;
        }

        List<InvestStockPool> activePool = poolRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(p -> !"exited".equalsIgnoreCase(p.getStatus()))
                .filter(p -> p.getTargetBuyPrice() != null || p.getTargetSellPrice() != null)
                .collect(Collectors.toList());

        if (activePool.isEmpty()) {
            log.debug("股票池中无需要监控的标的");
            return;
        }

        List<String> codes = activePool.stream().map(InvestStockPool::getStockCode).distinct().toList();
        // 实时价格统一走 a-stock-data 实时接口；trade_stock_daily 收盘价有同步延迟、不准确
        Map<String, BigDecimal> latestPriceMap = aStockDataQuoteService.fetchQuotes(codes).values().stream()
                .filter(snapshot -> snapshot.latestPrice() != null)
                .collect(Collectors.toMap(
                        snapshot -> snapshot.stockCode() == null ? "" : snapshot.stockCode().toUpperCase(java.util.Locale.ROOT),
                        snapshot -> snapshot.latestPrice(),
                        (a, b) -> a
                ));
        Map<String, String> nameMap = basicRepository.findByStockCodeIn(codes).stream()
                .collect(Collectors.toMap(TradeStockBasic::getStockCode, TradeStockBasic::getStockName, (a, b) -> a));

        int triggered = 0;
        LocalDateTime now = LocalDateTime.now();

        for (InvestStockPool pool : activePool) {
            BigDecimal close = latestPriceMap.get(pool.getStockCode());
            if (close == null) continue;

            String currentState = pool.getAlertState() == null ? STATE_NONE : pool.getAlertState();
            String desiredState = STATE_NONE;

            if (pool.getTargetBuyPrice() != null && close.compareTo(pool.getTargetBuyPrice()) <= 0) {
                desiredState = STATE_BUY;
            } else if (pool.getTargetSellPrice() != null && close.compareTo(pool.getTargetSellPrice()) >= 0) {
                desiredState = STATE_SELL;
            }

            if (desiredState.equals(currentState)) {
                continue;
            }

            // 触发新的提醒：检查冷却期
            if (!STATE_NONE.equals(desiredState) && pool.getLastAlertAt() != null
                    && pool.getLastAlertAt().plusMinutes(cfg.getCooldownMinutes()).isAfter(now)) {
                log.debug("[{}] {} 状态需要切换到 {} 但仍在冷却期内", pool.getStockCode(), currentState, desiredState);
                continue;
            }

            String stockName = nameMap.getOrDefault(pool.getStockCode(), pool.getStockCode());
            pool.setAlertState(desiredState);

            if (STATE_NONE.equals(desiredState)) {
                // 价格回到中间区间，重置状态，不发送通知
                poolRepository.save(pool);
                log.info("[{}] {} 价格 {} 已回到正常区间，重置提醒状态", pool.getStockCode(), stockName, close);
                continue;
            }

            // 发送通知
            String title;
            String desp;
            if (STATE_BUY.equals(desiredState)) {
                title = String.format("📉 %s(%s) 触及买入价 %s", stockName, pool.getStockCode(), pool.getTargetBuyPrice());
                desp = buildAlertContent(stockName, pool, close, true);
            } else {
                title = String.format("📈 %s(%s) 触及卖出价 %s", stockName, pool.getStockCode(), pool.getTargetSellPrice());
                desp = buildAlertContent(stockName, pool, close, false);
            }
            boolean sent = notificationService.sendServerChan(title, desp);
            if (sent) {
                pool.setLastAlertAt(now);
                triggered++;
            }
            poolRepository.save(pool);
        }

        if (triggered > 0) {
            log.info("价格监控本次触发 {} 条推送（共 {} 只标的）", triggered, activePool.size());
        }
    }

    private String buildAlertContent(String stockName, InvestStockPool pool, BigDecimal close, boolean isBuy) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(stockName).append("（").append(pool.getStockCode()).append("）\n\n");
        sb.append("**当前价**：").append(close).append("\n\n");
        if (isBuy) {
            sb.append("**目标买入价**：").append(pool.getTargetBuyPrice()).append(" ✅ 已触发\n\n");
            if (pool.getTargetSellPrice() != null) {
                sb.append("**目标卖出价**：").append(pool.getTargetSellPrice()).append("\n\n");
            }
        } else {
            sb.append("**目标卖出价**：").append(pool.getTargetSellPrice()).append(" ✅ 已触发\n\n");
            if (pool.getTargetBuyPrice() != null) {
                sb.append("**目标买入价**：").append(pool.getTargetBuyPrice()).append("\n\n");
            }
        }
        if (pool.getUndervaluedPrice() != null || pool.getFairPrice() != null || pool.getOvervaluedPrice() != null) {
            sb.append("**估值锚点**：低估 ").append(safe(pool.getUndervaluedPrice()))
                    .append(" / 合理 ").append(safe(pool.getFairPrice()))
                    .append(" / 高估 ").append(safe(pool.getOvervaluedPrice())).append("\n\n");
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
