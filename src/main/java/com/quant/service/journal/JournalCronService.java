package com.quant.service.journal;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.config.JournalProperties;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import com.quant.service.notification.NotificationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class JournalCronService {

  private final JournalTradeRepository repo;
  private final NotificationService notificationService;
  private final JournalProperties props;

  @Scheduled(cron = "${journal.refresh-cron:0 30 15 * * MON-FRI}")
  @Transactional
  public void scheduledRefresh() {
    if (props.getRefreshEnabled() == null || !props.getRefreshEnabled()) return;
    log.info("[JournalCron] 盘后刷新开始");
    for (JournalTrade t : repo.findAllOpen()) {
      try {
        BigDecimal current = fetchPrice(t.getStockCode());
        if (current == null) continue;
        refreshOpenTrade(t, current);
      } catch (Exception e) {
        log.warn("[JournalCron] {} 处理失败: {}", t.getStockCode(), e.getMessage());
      }
    }
    log.info("[JournalCron] 盘后刷新结束");
  }

  /**
   * Test-friendly overload — refresh only the trade matching stockCode, with given currentPrice.
   */
  public void refreshOpenTrades(String stockCode, BigDecimal currentPrice) {
    for (JournalTrade t : repo.findAllOpenByStockCode(stockCode)) {
      refreshOpenTrade(t, currentPrice);
    }
  }

  private void refreshOpenTrade(JournalTrade t, BigDecimal current) {
    if (t.getTargetPrice() != null && current.compareTo(t.getTargetPrice()) >= 0) {
      t.setExitPrice(t.getTargetPrice());
      t.setExitDate(java.time.LocalDateTime.now());
      t.setExitReason(JournalTrade.ExitReason.target_hit);
      t.setIsOpen(0);
      BigDecimal pnl =
          t.getTargetPrice()
              .subtract(t.getEntryPrice())
              .multiply(new BigDecimal(t.getEntryShares()))
              .setScale(2, RoundingMode.HALF_UP);
      t.setPnlAmount(pnl);
      BigDecimal totalRisk = t.getInitialRisk().multiply(new BigDecimal(t.getEntryShares()));
      if (totalRisk.signum() > 0) {
        t.setRMultiple(pnl.divide(totalRisk, 4, RoundingMode.HALF_UP));
      }
      t.setReviewNotes("系统自动平仓(目标触达)");
      repo.save(t);
      notificationService.sendServerChan(
          String.format(
              "[自动平仓] %s (%s)", t.getStockCode(), t.getStockName() != null ? t.getStockName() : ""),
          String.format(
              "入场 %.2f → 目标 %.2f\nR 倍数 %s",
              t.getEntryPrice(),
              t.getTargetPrice(),
              t.getRMultiple() != null ? t.getRMultiple().toString() : "N/A"));
    }
  }

  private BigDecimal fetchPrice(String stockCode) {
    try {
      var rest = new org.springframework.web.client.RestTemplate();
      String url =
          "http://localhost:8080/gp/api/xiebo-invest/quote?keyword="
              + java.net.URLEncoder.encode(stockCode, java.nio.charset.StandardCharsets.UTF_8);
      var body = rest.getForObject(url, java.util.Map.class);
      if (body == null) return null;
      Object p = body.get("price");
      if (p == null && body.get("quote") instanceof java.util.Map q) p = q.get("price");
      return p == null ? null : new BigDecimal(p.toString());
    } catch (Exception e) {
      log.debug("[JournalCron] 拉价失败 {}: {}", stockCode, e.getMessage());
      return null;
    }
  }
}
