package com.quant.service.etfmodel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.dto.etfmodel.EtfTradeRequest;
import com.quant.dto.etfmodel.EtfTradeResult;
import com.quant.entity.EtfModelConfig;
import com.quant.entity.EtfNavSnapshot;
import com.quant.entity.EtfPool;
import com.quant.entity.EtfTrade;
import com.quant.repository.EtfModelConfigRepository;
import com.quant.repository.EtfNavSnapshotRepository;
import com.quant.repository.EtfPoolRepository;
import com.quant.repository.EtfTradeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 省心 ETF 业务核心：摊薄成本核算、录单纪律校验（警告不阻断）、档位/回补状态流转、净值快照。
 *
 * <p>档位标志（tp1/tp2/sl1/sl2）与回补基础状态由交易流水推导（{@link #replayLedger}），
 * 因此删除/补录交易后状态仍然一致；清仓（份额归零）自动开启新周期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EtfModelService {

  public static final int MAX_POOL_SIZE = 10;
  public static final int MAX_BATCHES = 3;

  private static final List<String> BATCH_TYPES = List.of(EtfTrade.TYPE_OPEN, EtfTrade.TYPE_ADD);
  private static final Set<String> SL_TYPES = Set.of(EtfTrade.TYPE_SL1, EtfTrade.TYPE_SL2);

  private final EtfPoolRepository poolRepo;
  private final EtfTradeRepository tradeRepo;
  private final EtfModelConfigRepository configRepo;
  private final EtfNavSnapshotRepository navRepo;

  /* ─────────── 配置 ─────────── */

  public EtfModelConfig config() {
    return configRepo
        .findById(1L)
        .orElseGet(
            () -> {
              EtfModelConfig c = new EtfModelConfig();
              c.setId(1L);
              c.setTotalCapital(BigDecimal.valueOf(100000));
              c.setSingleMaxPct(BigDecimal.valueOf(20));
              c.setPortfolioMaxPct(BigDecimal.valueOf(70));
              c.setLightBatchMaxAmount(BigDecimal.valueOf(5000));
              c.setMidBatchMinAmount(BigDecimal.valueOf(10000));
              c.setMidBatchMaxAmount(BigDecimal.valueOf(20000));
              c.setBigRiseThresholdPct(BigDecimal.valueOf(15));
              c.setPortfolioDrawdownPct(BigDecimal.valueOf(20));
              c.setCalmDays(7);
              c.setInceptionDate(LocalDate.of(2026, 6, 23));
              return configRepo.save(c);
            });
  }

  /** 是否处于组合级保命冷静期（买入/加仓提醒附加冷静标注） */
  public boolean inCalmPeriod() {
    LocalDate until = config().getCalmUntil();
    return until != null && !until.isBefore(LocalDate.now());
  }

  /* ─────────── 持仓计算 ─────────── */

  public List<EtfPositionView> activePositions() {
    List<EtfPositionView> out = new ArrayList<>();
    for (EtfPool pool : poolRepo.findByStatusOrderByIdAsc(EtfPool.STATUS_ACTIVE)) {
      out.add(positionView(pool));
    }
    return out;
  }

  public EtfPositionView positionView(EtfPool pool) {
    List<EtfTrade> trades = tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(pool.getId());
    int shares = 0;
    BigDecimal netInvested = BigDecimal.ZERO;
    int batches = 0;
    for (EtfTrade t : trades) {
      if (t.isBuy()) {
        shares += t.getShares();
        netInvested = netInvested.add(t.getAmount());
        if (BATCH_TYPES.contains(t.getTradeType())) {
          batches++;
        }
      } else {
        shares -= t.getShares();
        netInvested = netInvested.subtract(t.getAmount());
      }
      if (shares <= 0) {
        // 清仓 → 新周期：批次与净投入清零（浮盈/浮亏已实现，不影响下一轮摊薄成本）
        shares = Math.max(shares, 0);
        netInvested = BigDecimal.ZERO;
        batches = 0;
      }
    }
    BigDecimal dilutedCost = null;
    if (shares > 0 && netInvested.compareTo(BigDecimal.ZERO) > 0) {
      dilutedCost = netInvested.divide(BigDecimal.valueOf(shares), 3, RoundingMode.HALF_UP);
    }
    return EtfPositionView.builder()
        .poolId(pool.getId())
        .stockCode(pool.getStockCode())
        .stockName(pool.getStockName())
        .category(pool.getCategory())
        .shares(shares)
        .netInvested(netInvested)
        .dilutedCost(dilutedCost)
        .batchesUsed(batches)
        .tp1Done(intBool(pool.getTp1Done()))
        .tp2Done(intBool(pool.getTp2Done()))
        .sl1Done(intBool(pool.getSl1Done()))
        .sl2Done(intBool(pool.getSl2Done()))
        .recoupStatus(pool.getRecoupStatus())
        .build();
  }

  /* ─────────── 录单 ─────────── */

  @Transactional
  public EtfTradeResult recordTrade(EtfTradeRequest req) {
    EtfPool pool = resolvePool(req);
    if (req.getDirection() == null
        || (!EtfTrade.DIR_BUY.equals(req.getDirection())
            && !EtfTrade.DIR_SELL.equals(req.getDirection()))) {
      throw new IllegalArgumentException("direction 必须为 BUY 或 SELL");
    }
    if (req.getPrice() == null || req.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("price 必须大于 0");
    }
    if (req.getShares() == null || req.getShares() <= 0) {
      throw new IllegalArgumentException("shares 必须大于 0");
    }

    EtfPositionView before = positionView(pool);
    if (EtfTrade.DIR_SELL.equals(req.getDirection()) && req.getShares() > before.getShares()) {
      throw new IllegalArgumentException(
          "卖出份额 " + req.getShares() + " 超过当前持有 " + before.getShares());
    }

    EtfTrade trade = new EtfTrade();
    trade.setPoolId(pool.getId());
    trade.setStockCode(pool.getStockCode());
    trade.setDirection(req.getDirection());
    trade.setTradeType(req.getTradeType() == null ? EtfTrade.TYPE_OTHER : req.getTradeType());
    trade.setPrice(req.getPrice());
    trade.setShares(req.getShares());
    trade.setAmount(
        req.getAmount() != null
            ? req.getAmount()
            : req.getPrice()
                .multiply(BigDecimal.valueOf(req.getShares()))
                .setScale(2, RoundingMode.HALF_UP));
    trade.setTradeTime(req.getTradeTime() != null ? req.getTradeTime() : LocalDateTime.now());
    trade.setSource("MANUAL");
    trade.setMemo(req.getMemo());
    tradeRepo.save(trade);

    replayLedger(pool);
    List<String> warnings = validateAfterTrade(pool, trade, before);
    return new EtfTradeResult(trade, warnings);
  }

  @Transactional
  public void deleteTrade(Long tradeId) {
    EtfTrade trade =
        tradeRepo
            .findById(tradeId)
            .orElseThrow(() -> new IllegalArgumentException("交易不存在: " + tradeId));
    tradeRepo.delete(trade);
    poolRepo.findById(trade.getPoolId()).ifPresent(this::replayLedger);
  }

  /**
   * 由交易流水重放档位/回补状态：
   *
   * <ul>
   *   <li>SELL TP1/TP2/SL1/SL2/TRAIL_EXIT → 置对应 done 标志
   *   <li>SL1/SL2 卖出 → 回补状态 WAITING（等待周K平稳）
   *   <li>RECOUP 买入 或 清仓（份额归零）→ 回补状态归位 NONE、档位清零（新周期）
   *   <li>周任务标记的 READY 状态在流水未变化时保留
   * </ul>
   */
  @Transactional
  public void replayLedger(EtfPool pool) {
    List<EtfTrade> trades = tradeRepo.findByPoolIdOrderByTradeTimeAscIdAsc(pool.getId());
    int shares = 0;
    boolean tp1 = false;
    boolean tp2 = false;
    boolean sl1 = false;
    boolean sl2 = false;
    boolean recoupWaiting = false;
    for (EtfTrade t : trades) {
      if (t.isBuy()) {
        shares += t.getShares();
        if (EtfTrade.TYPE_RECOUP.equals(t.getTradeType())) {
          recoupWaiting = false;
        }
      } else {
        shares -= t.getShares();
        switch (t.getTradeType()) {
          case EtfTrade.TYPE_TP1 -> tp1 = true;
          case EtfTrade.TYPE_TP2 -> {
            tp1 = true;
            tp2 = true;
          }
          case EtfTrade.TYPE_SL1 -> {
            sl1 = true;
            recoupWaiting = true;
          }
          case EtfTrade.TYPE_SL2 -> {
            sl1 = true;
            sl2 = true;
            recoupWaiting = true;
          }
          default -> {}
        }
      }
      if (shares <= 0) {
        shares = Math.max(shares, 0);
        tp1 = tp2 = sl1 = sl2 = false;
        // 行业 -18% 清仓后仍可能周K平稳回补 → 保留 WAITING；TRAIL_EXIT/普通清仓 → 不回补
        if (!SL_TYPES.contains(t.getTradeType())) {
          recoupWaiting = false;
        }
      }
    }
    pool.setTp1Done(tp1 ? 1 : 0);
    pool.setTp2Done(tp2 ? 1 : 0);
    pool.setSl1Done(sl1 ? 1 : 0);
    pool.setSl2Done(sl2 ? 1 : 0);
    if (recoupWaiting) {
      // 已是 READY（周任务判定）则保留，否则进入 WAITING
      if (!EtfPool.RECOUP_READY.equals(pool.getRecoupStatus())) {
        pool.setRecoupStatus(EtfPool.RECOUP_WAITING);
      }
    } else {
      pool.setRecoupStatus(EtfPool.RECOUP_NONE);
      pool.setRecoupWeeks(0);
    }
    poolRepo.save(pool);
  }

  /** 录单后的纪律校验（模型规则），返回警告列表，不阻断保存。 */
  private List<String> validateAfterTrade(EtfPool pool, EtfTrade trade, EtfPositionView before) {
    List<String> warnings = new ArrayList<>();
    EtfModelConfig cfg = config();
    BigDecimal capital = cfg.getTotalCapital();

    if (trade.isBuy()) {
      if (BATCH_TYPES.contains(trade.getTradeType()) && before.getBatchesUsed() + 1 > MAX_BATCHES) {
        warnings.add(
            "该支已用 " + (before.getBatchesUsed() + 1) + " 个买入批次，超过“1建仓+2加仓”共 " + MAX_BATCHES + " 次上限");
      }
      BigDecimal singleCap =
          capital.multiply(cfg.getSingleMaxPct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      BigDecimal newNet = before.getNetInvested().add(trade.getAmount());
      if (newNet.compareTo(singleCap) > 0) {
        warnings.add("单支净投入 " + newNet + " 元，超过单支上限 " + singleCap + " 元（总资金的 "
            + cfg.getSingleMaxPct().stripTrailingZeros().toPlainString() + "%）");
      }
      BigDecimal portfolioCap =
          capital
              .multiply(cfg.getPortfolioMaxPct())
              .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      BigDecimal totalInvested = totalNetInvested();
      if (totalInvested.compareTo(portfolioCap) > 0) {
        warnings.add("组合净投入 " + totalInvested + " 元，超过组合上限 " + portfolioCap + " 元（须永远留 "
            + BigDecimal.valueOf(100).subtract(cfg.getPortfolioMaxPct()).stripTrailingZeros().toPlainString()
            + "% 现金）");
      }
      if (inCalmPeriod()) {
        warnings.add("⚠️ 组合保命线冷静期内（至 " + cfg.getCalmUntil() + "），按纪律应暂缓买入/加仓");
      }
    }
    return warnings;
  }

  /** 全池净投入合计（当前周期） */
  public BigDecimal totalNetInvested() {
    BigDecimal sum = BigDecimal.ZERO;
    for (EtfPositionView p : activePositions()) {
      if (p.getNetInvested() != null && p.getShares() > 0) {
        sum = sum.add(p.getNetInvested());
      }
    }
    return sum;
  }

  /* ─────────── 净值快照与保命线 ─────────── */

  public record NavResult(EtfNavSnapshot snapshot, boolean guardTriggered) {}

  /**
   * 写入/更新今日净值快照，滚动更新历史最高点；回撤 ≥ 阈值时返回 guardTriggered=true 并设置冷静期。
   *
   * @param priceByCode 各持仓 ETF 最新价（大写带后缀代码 → 价格）
   */
  @Transactional
  public NavResult recordNavSnapshot(Map<String, BigDecimal> priceByCode) {
    EtfModelConfig cfg = config();
    BigDecimal marketValue = BigDecimal.ZERO;
    BigDecimal invested = BigDecimal.ZERO;
    for (EtfPositionView p : activePositions()) {
      if (p.getShares() <= 0) {
        continue;
      }
      invested = invested.add(p.getNetInvested());
      BigDecimal price = priceByCode.get(p.getStockCode());
      if (price != null) {
        marketValue = marketValue.add(price.multiply(BigDecimal.valueOf(p.getShares())));
      } else {
        // 无行情时按净投入估值，避免快照失真
        marketValue = marketValue.add(p.getNetInvested().max(BigDecimal.ZERO));
      }
    }
    marketValue = marketValue.setScale(2, RoundingMode.HALF_UP);
    BigDecimal cash = cfg.getTotalCapital().subtract(invested).setScale(2, RoundingMode.HALF_UP);
    BigDecimal totalAsset = marketValue.add(cash);

    BigDecimal peak = cfg.getNavPeak();
    if (peak == null || totalAsset.compareTo(peak) > 0) {
      peak = totalAsset;
      cfg.setNavPeak(peak);
      cfg.setNavPeakDate(LocalDate.now());
    }
    BigDecimal drawdown = EtfSignalEngine.drawdownPct(totalAsset, peak);

    LocalDate today = LocalDate.now();
    EtfNavSnapshot snap =
        navRepo
            .findBySnapDate(today)
            .orElseGet(
                () -> {
                  EtfNavSnapshot s = new EtfNavSnapshot();
                  s.setSnapDate(today);
                  return s;
                });
    snap.setMarketValue(marketValue);
    snap.setCash(cash);
    snap.setTotalAsset(totalAsset);
    snap.setPeakAsset(peak);
    snap.setDrawdownPct(drawdown);
    navRepo.save(snap);

    boolean guard =
        drawdown != null && drawdown.compareTo(cfg.getPortfolioDrawdownPct()) >= 0;
    if (guard) {
      LocalDate calmUntil = today.plusDays(cfg.getCalmDays() == null ? 7 : cfg.getCalmDays());
      if (cfg.getCalmUntil() == null || cfg.getCalmUntil().isBefore(calmUntil)) {
        cfg.setCalmUntil(calmUntil);
      }
    }
    configRepo.save(cfg);
    return new NavResult(snap, guard);
  }

  /* ─────────── helpers ─────────── */

  private EtfPool resolvePool(EtfTradeRequest req) {
    if (req.getPoolId() != null) {
      return poolRepo
          .findById(req.getPoolId())
          .orElseThrow(() -> new IllegalArgumentException("ETF 不存在: id=" + req.getPoolId()));
    }
    if (req.getStockCode() != null && !req.getStockCode().isBlank()) {
      String code = EtfKlineService.normalize(req.getStockCode());
      return poolRepo
          .findByStockCode(code)
          .orElseThrow(() -> new IllegalArgumentException("ETF 不在池内: " + code));
    }
    throw new IllegalArgumentException("poolId 或 stockCode 必填");
  }

  private static boolean intBool(Integer v) {
    return v != null && v == 1;
  }
}
