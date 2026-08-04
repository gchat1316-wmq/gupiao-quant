package com.quant.service.etfmodel;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.quant.config.EtfModelProperties;
import com.quant.entity.EtfAlert;
import com.quant.entity.EtfPool;
import com.quant.repository.EtfAlertRepository;
import com.quant.repository.EtfPoolRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;
import com.quant.service.notification.NotificationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 省心 ETF 监控调度：
 *
 * <ul>
 *   <li>盘中每分钟：实时价 → 止盈/止损阈值提醒（冷却去重）
 *   <li>收盘兜底（15:20）：日K同步 → 阈值复查 + 移动止盈（收盘跌破20日线）→ 净值快照 + 组合保命线
 *   <li>周五盘后：回补条件检查（周K连续2周收在5日线上方 → 可回补）
 * </ul>
 *
 * <p>冷静期内买入/加仓类提醒照常推送，但附加“⚠️ 冷静期”标注（用户确认口径 2026-08-04）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EtfMonitorJob {

  private final EtfModelProperties props;
  private final EtfModelService modelService;
  private final EtfSignalEngine engine;
  private final EtfKlineService klineService;
  private final EtfPoolRepository poolRepo;
  private final EtfAlertRepository alertRepo;
  private final AStockDataQuoteService quoteService;
  private final NotificationService notificationService;

  /* ─────────── 盘中扫描 ─────────── */

  @Scheduled(cron = "${etf-model.intraday-cron:0 */1 9-15 * * MON-FRI}")
  public void intradayScan() {
    if (!props.isEnabled()) return;
    if (props.isRequireTradingTime() && !isTradingTime()) return;
    try {
      scanThresholds();
    } catch (Exception e) {
      log.error("etf intraday scan 异常", e);
    }
  }

  /** 手动/盘中阈值扫描。返回触发推送条数。 */
  public int scanThresholds() {
    List<EtfPositionView> positions =
        modelService.activePositions().stream().filter(p -> p.getShares() > 0).toList();
    if (positions.isEmpty()) return 0;

    Map<String, BigDecimal> prices = fetchPrices(positions);
    int triggered = 0;
    for (EtfPositionView pos : positions) {
      BigDecimal price = prices.get(pos.getStockCode());
      if (price == null) continue;
      for (EtfSignal sig : engine.evaluateThresholds(pos, price)) {
        if (pushIfCool(sig)) triggered++;
      }
    }
    if (triggered > 0) {
      log.info("etf monitor 本次触发 {} 条推送（共 {} 只持仓）", triggered, positions.size());
    }
    return triggered;
  }

  /* ─────────── 收盘兜底 ─────────── */

  @Scheduled(cron = "${etf-model.eod-cron:0 20 15 * * MON-FRI}")
  public void eodCheck() {
    if (!props.isEnabled()) return;
    try {
      List<EtfPool> pools = poolRepo.findByStatusOrderByIdAsc(EtfPool.STATUS_ACTIVE);
      klineService.syncDaily(pools.stream().map(EtfPool::getStockCode).toList(), props.getKlineDaysBack());

      // 阈值兜底复查（用收盘后的最新价）
      scanThresholds();

      // 移动止盈：收盘跌破 20 日线才卖（仅收盘判定）
      List<EtfPositionView> positions = modelService.activePositions();
      Map<String, BigDecimal> closes = new HashMap<>();
      for (EtfPositionView pos : positions) {
        EtfKlineService.MaSnapshot ma = klineService.maSnapshot(pos.getStockCode());
        if (ma.latestClose() != null) {
          closes.put(pos.getStockCode(), ma.latestClose());
        }
        EtfSignal trail = engine.evaluateTrailExit(pos, ma.latestClose(), ma.ma20());
        if (trail != null) {
          pushIfCool(trail);
        }
      }

      // 净值快照 + 组合级保命线（实时价优先，缺失回退收盘价）
      Map<String, BigDecimal> prices = fetchPrices(positions);
      closes.forEach(prices::putIfAbsent);
      EtfModelService.NavResult nav = modelService.recordNavSnapshot(prices);
      if (nav.guardTriggered()) {
        EtfSignal guard =
            engine.evaluatePortfolioGuard(
                nav.snapshot().getTotalAsset(),
                nav.snapshot().getPeakAsset(),
                modelService.config().getPortfolioDrawdownPct(),
                modelService.config().getCalmDays() == null ? 7 : modelService.config().getCalmDays());
        if (guard != null) {
          pushIfCool(guard);
        }
      }
    } catch (Exception e) {
      log.error("etf eod check 异常", e);
    }
  }

  /* ─────────── 周五回补检查 ─────────── */

  @Scheduled(cron = "${etf-model.weekly-cron:0 40 15 * * FRI}")
  public void weeklyRecoupCheck() {
    if (!props.isEnabled()) return;
    try {
      for (EtfPool pool : poolRepo.findByStatusOrderByIdAsc(EtfPool.STATUS_ACTIVE)) {
        if (!EtfPool.RECOUP_WAITING.equals(pool.getRecoupStatus())) {
          continue;
        }
        EtfKlineService.MaSnapshot ma = klineService.maSnapshot(pool.getStockCode());
        if (engine.weeklyCloseAboveMa5(ma.latestClose(), ma.ma5())) {
          int weeks = (pool.getRecoupWeeks() == null ? 0 : pool.getRecoupWeeks()) + 1;
          pool.setRecoupWeeks(weeks);
          if (weeks >= 2) {
            pool.setRecoupStatus(EtfPool.RECOUP_READY);
            pushIfCool(engine.recoupReady(modelService.positionView(pool), ma.latestClose(), ma.ma5()));
          }
        } else {
          pool.setRecoupWeeks(0);
        }
        poolRepo.save(pool);
      }
    } catch (Exception e) {
      log.error("etf weekly recoup check 异常", e);
    }
  }

  /* ─────────── 推送 ─────────── */

  /** 冷却去重 + 冷静期标注 + Server酱推送 + etf_alert 落库。 */
  boolean pushIfCool(EtfSignal sig) {
    Optional<EtfAlert> last =
        sig.getStockCode() == null
            ? alertRepo.findFirstBySignalTypeOrderByTriggerAtDesc(sig.getSignalType())
            : alertRepo.findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(
                sig.getStockCode(), sig.getSignalType());
    if (last.isPresent()
        && last.get().getTriggerAt() != null
        && last.get()
            .getTriggerAt()
            .plusMinutes(props.getCooldownMinutes())
            .isAfter(LocalDateTime.now())) {
      return false;
    }

    String title = sig.getTitle();
    String content = sig.getContent();
    if (sig.isBuyAdvice() && modelService.inCalmPeriod()) {
      title = "⚠️冷静期 · " + title;
      content =
          content
              + "\n\n> ⚠️ 组合保命线冷静期内（至 "
              + modelService.config().getCalmUntil()
              + "），按纪律应暂缓买入/加仓，请谨慎操作。";
    }

    EtfAlert alert = new EtfAlert();
    alert.setStockCode(sig.getStockCode());
    alert.setSignalType(sig.getSignalType());
    alert.setTitle(title);
    alert.setContent(content);
    alert.setTriggerPrice(sig.getTriggerPrice());
    alert.setTriggerAt(sig.getTriggeredAt() != null ? sig.getTriggeredAt() : LocalDateTime.now());

    boolean sent = false;
    try {
      sent = notificationService.sendServerChan(title, content);
    } catch (Exception e) {
      log.warn("[{}] Server酱推送失败 (signal={}): {}", sig.getStockCode(), sig.getSignalType(), e.getMessage());
    }
    alert.setPushed(sent ? 1 : 0);
    try {
      alertRepo.save(alert);
    } catch (Exception e) {
      log.warn("[{}] etf_alert 持久化失败: {}", sig.getStockCode(), e.getMessage());
    }
    return sent;
  }

  private Map<String, BigDecimal> fetchPrices(List<EtfPositionView> positions) {
    Map<String, BigDecimal> prices = new HashMap<>();
    try {
      List<String> codes = positions.stream().map(EtfPositionView::getStockCode).toList();
      Map<String, AStockDataQuoteService.QuoteSnapshot> quotes = quoteService.fetchQuotes(codes);
      quotes.forEach(
          (code, q) -> {
            if (q.latestPrice() != null) {
              prices.put(code, q.latestPrice());
            }
          });
    } catch (Exception e) {
      log.warn("etf 拉取实时行情失败: {}", e.getMessage());
    }
    return prices;
  }

  private boolean isTradingTime() {
    LocalTime now = LocalTime.now();
    return (now.isAfter(LocalTime.of(9, 29)) && now.isBefore(LocalTime.of(11, 31)))
        || (now.isAfter(LocalTime.of(12, 59)) && now.isBefore(LocalTime.of(15, 1)));
  }
}
