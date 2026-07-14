package com.quant.service;

import com.quant.config.XieboRecentAlertProperties;
import com.quant.entity.InvestAlert;
import com.quant.entity.UserStockSubscription;
import com.quant.repository.InvestAlertRepository;
import com.quant.repository.UserStockSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class XieboRecentAlertJob {

    private final UserStockSubscriptionRepository subRepo;
    private final AStockDataQuoteService quoteService;
    private final NotificationService notificationService;
    private final InvestAlertRepository alertRepo;
    private final XieboRecentAlertProperties props;

    @Scheduled(cron = "${xiebo-recent-alert.cron:0 */5 9-15 * * MON-FRI}")
    public void scheduledScan() {
        try {
            scan();
        } catch (Exception e) {
            log.error("XieboRecentAlertJob.scheduledScan 异常", e);
        }
    }

    @Transactional
    public int scan() {
        if (!props.isEnabled()) {
            log.debug("XieboRecentAlertJob 已禁用");
            return 0;
        }

        List<UserStockSubscription> subs = subRepo.findAllEnabledWithPrice();
        if (subs == null || subs.isEmpty()) return 0;

        // 1 次批量拉价
        List<String> codes = subs.stream().map(UserStockSubscription::getStockCode).distinct().toList();
        Map<String, AStockDataQuoteService.QuoteSnapshot> quotes;
        try {
            quotes = quoteService.fetchQuotes(codes);
        } catch (Exception e) {
            log.warn("拉价失败,本次跳过: {}", e.getMessage());
            return 0;
        }

        int triggered = 0;
        for (UserStockSubscription s : subs) {
            try {
                if (scanOne(s, quotes.get(s.getStockCode()))) triggered++;
            } catch (Exception e) {
                log.warn("[{}] scanOne 异常: {}", s.getStockCode(), e.getMessage());
            }
        }
        if (triggered > 0) {
            log.info("xiebo recent alert scan 触发 {} 条推送(共 {} 只订阅)", triggered, subs.size());
        }
        return triggered;
    }

    private boolean scanOne(UserStockSubscription s, AStockDataQuoteService.QuoteSnapshot q) {
        if (q == null || q.latestPrice() == null) return false;
        BigDecimal cur = q.latestPrice();

        boolean any = false;
        if (tryFire(s, cur, s.getPriceBuy(), s.getAlertBuyTriggeredAt(),
                "xiebo_recent_buy", "买入")) {
            s.setAlertBuyTriggeredAt(LocalDateTime.now());
            any = true;
        }
        if (tryFire(s, cur, s.getPriceStopLoss(), s.getAlertStopLossTriggeredAt(),
                "xiebo_recent_stop_loss", "止损")) {
            s.setAlertStopLossTriggeredAt(LocalDateTime.now());
            any = true;
        }
        if (tryFire(s, cur, s.getPriceAddPosition(), s.getAlertAddPositionTriggeredAt(),
                "xiebo_recent_add", "加仓")) {
            s.setAlertAddPositionTriggeredAt(LocalDateTime.now());
            any = true;
        }
        if (tryFire(s, cur, s.getPriceReducePosition(), s.getAlertReducePositionTriggeredAt(),
                "xiebo_recent_reduce", "减仓")) {
            s.setAlertReducePositionTriggeredAt(LocalDateTime.now());
            any = true;
        }
        if (tryFire(s, cur, s.getPriceClearPosition(), s.getAlertClearPositionTriggeredAt(),
                "xiebo_recent_clear", "清仓")) {
            s.setAlertClearPositionTriggeredAt(LocalDateTime.now());
            any = true;
        }
        if (any) subRepo.save(s);
        return any;
    }

    private boolean tryFire(UserStockSubscription s, BigDecimal cur, BigDecimal price,
                            LocalDateTime triggeredAt, String signalType, String label) {
        if (price == null) return false;
        boolean hit = switch (signalType) {
            case "xiebo_recent_buy", "xiebo_recent_stop_loss", "xiebo_recent_add"
                    -> cur.compareTo(price) <= 0;
            default -> cur.compareTo(price) >= 0;
        };
        if (!hit || triggeredAt != null) return false;

        InvestAlert alert = new InvestAlert();
        alert.setStockCode(s.getStockCode());
        alert.setSignalType(signalType);
        alert.setLevel(2);
        alert.setTitle("[谢博·近期关注] " + label + " · " + s.getStockCode());
        alert.setContent(String.format(
                "股票 %s 当前价 %s,触发%s提醒(阈值 %s,订阅 userId=%d)",
                s.getStockCode(), cur, label, price, s.getUserId()));
        alert.setTriggerPrice(price);
        alert.setTriggerAt(LocalDateTime.now());
        alert.setChannels("serverchan");
        alert.setPushed(0);
        try {
            alertRepo.save(alert);
        } catch (Exception e) {
            log.warn("[{}] InvestAlert 持久化失败: {}", s.getStockCode(), e.getMessage());
        }

        // 推送(订阅级 SCKEY;用户级 fallback 由 XieboRecentSubscriptionService 在 upsert 时预填)
        String sendKey = s.getServerchanSendKey();
        if (sendKey == null || sendKey.isBlank()) {
            log.warn("[{}] 无 SCKEY,仅写 alert 不推送", s.getStockCode());
            return true;
        }
        try {
            boolean sent = notificationService.sendServerChan(alert.getTitle(), alert.getContent(), sendKey);
            if (sent) alert.setPushed(1);
        } catch (Exception e) {
            log.warn("[{}] Server酱推送失败: {}", s.getStockCode(), e.getMessage());
        }
        return true;
    }
}
