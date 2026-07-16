package com.quant.service.potential;

import com.quant.entity.InvestPositionCommon;
import com.quant.entity.PotentialPool;
import com.quant.entity.PotentialPositionFill;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.PotentialPoolRepository;
import com.quant.repository.PotentialPositionFillRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.service.techai.TechAiAtrCalculator;
import com.quant.service.techai.TechAiPositionEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 潜力监控 · 持仓聚合计算。
 *
 * <p>封装：
 *
 * <ul>
 *   <li>{@link #getOrCreatePosition(String)}：取/初始化潜在持仓记录（poolType = potential）
 *   <li>{@link #recomputeAggregates(InvestPositionCommon)}：按成交记录重算平均成本/已实现盈亏/峰值/状态
 *   <li>{@link #effectiveTargetPrice(InvestPositionCommon)}：有效目标价 = pos.targetSellPrice ?? entry × (1 + takeProfitPct/100)
 *   <li>{@link #isAtrMode(InvestPositionCommon)}：是否启用 ATR
 *   <li>{@link #atrFor(InvestPositionCommon, String)}：取 ATR 数值
 *   <li>{@link #defaultTargetPrice(BigDecimal, BigDecimal)}：根据 entry/takeProfitPct 推算目标价
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PotentialPositionCalculator {

  public static final String POOL_TYPE_POTENTIAL = "potential";

  private final PotentialPoolRepository poolRepository;
  private final PotentialPositionFillRepository fillRepository;
  private final InvestPositionCommonRepository positionRepository;
  private final TradeStockDailyRepository dailyRepository;
  private final TechAiPositionEngine positionEngine;
  private final TechAiAtrCalculator atrCalculator;

  /**
   * 取指定股票的潜在持仓记录；不存在则创建默认值（status=watching，lots=0）。
   */
  public InvestPositionCommon getOrCreatePosition(String stockCode) {
    return positionRepository.findByStockCodeAndPoolType(stockCode, POOL_TYPE_POTENTIAL)
        .orElseGet(() -> positionRepository.save(newDefaultPosition(stockCode, "watching")));
  }

  /**
   * 构造一份潜在监控默认持仓记录（未持久化）。单一来源：{@link #getOrCreatePosition(String)} 与
   * {@code PotentialService.addToPool} 都通过它来消除重复初始化逻辑。
   *
   * @param stockCode      股票代码
   * @param requestStatus  入参状态（{@code null} 或空白时回退到 "watching"）
   */
  public static InvestPositionCommon newDefaultPosition(String stockCode, String requestStatus) {
    InvestPositionCommon pos = new InvestPositionCommon();
    pos.setStockCode(stockCode);
    pos.setPoolType(POOL_TYPE_POTENTIAL);
    pos.setStatus(requestStatus == null || requestStatus.isBlank() ? "watching" : requestStatus);
    pos.setAlertState("none");
    pos.setPositionState("none");
    pos.setPositionLots(BigDecimal.ZERO);
    pos.setRealizedPnl(BigDecimal.ZERO);
    pos.setAddCount(0);
    pos.setTakeProfitDone(0);
    pos.setAddStepPct(BigDecimal.valueOf(10));
    pos.setTrailPct(BigDecimal.valueOf(10));
    pos.setAddSizeSchedule("1,1,1");
    pos.setTakeProfitPct(BigDecimal.valueOf(50));
    pos.setBreakevenAfterTp(1);
    pos.setUseAtr(0);
    pos.setAtrPeriod(14);
    pos.setAtrAddMult(BigDecimal.ONE);
    pos.setAtrTrailMult(BigDecimal.valueOf(2));
    return pos;
  }

  /**
   * 根据成交记录重算所有聚合字段：positionLots、avgCost、entryPrice、lastAddPrice、peakPrice、
   * stopPrice、totalInvested、openedAt、takeProfitDone、addCount、realizedPnl、
   * positionState（holding/scaled/exited/none）、status（watching/holding/exited）。
   *
   * <p>同时更新 stopPrice 通过 {@link TechAiPositionEngine#evaluate}。
   */
  public void recomputeAggregates(InvestPositionCommon position) {
    PotentialPool pool = poolRepository.findByStockCode(position.getStockCode())
        .orElseThrow(() -> new IllegalStateException("pool not found for " + position.getStockCode()));
    List<PotentialPositionFill> fills = fillRepository.findByPoolIdOrderByFilledAtAscIdAsc(pool.getId());
    BigDecimal target = position.getTargetSellPrice();

    BigDecimal lots = BigDecimal.ZERO;
    BigDecimal avg = null;
    BigDecimal realized = BigDecimal.ZERO;
    int addCount = 0;
    BigDecimal lastBuyPrice = null;
    BigDecimal entry = null;
    BigDecimal peak = null;
    LocalDateTime openedAt = null;
    boolean tpDone = false;
    boolean scaled = false;

    for (PotentialPositionFill fill : fills) {
      String action = fill.getAction();
      BigDecimal price = fill.getPrice();
      BigDecimal fl = fill.getLots();
      if ("open".equals(action) || "add".equals(action)) {
        if (lots.compareTo(BigDecimal.ZERO) <= 0) {
          avg = price;
          lots = fl;
          entry = price;
          if (target == null) {
            target = defaultTargetPrice(entry, position.getTakeProfitPct());
          }
          addCount = 0;
          peak = price;
          openedAt = fill.getFilledAt();
          tpDone = false;
          scaled = false;
        } else {
          BigDecimal newLots = lots.add(fl);
          avg = avg.multiply(lots).add(price.multiply(fl)).divide(newLots, 4, RoundingMode.HALF_UP);
          lots = newLots;
          addCount++;
          peak = peak == null ? price : peak.max(price);
        }
        lastBuyPrice = price;
      } else {
        BigDecimal sellLots = "clear".equals(action) ? lots : fl.min(lots);
        if (avg != null && sellLots.compareTo(BigDecimal.ZERO) > 0) {
          realized = realized.add(price.subtract(avg)
              .multiply(sellLots).multiply(BigDecimal.valueOf(TechAiPositionEngine.SHARES_PER_LOT)));
        }
        lots = lots.subtract(sellLots);
        if (target != null && price.compareTo(target) >= 0) {
          tpDone = true;
        }
        if (lots.compareTo(BigDecimal.ZERO) <= 0) {
          lots = BigDecimal.ZERO;
        } else {
          scaled = true;
        }
      }
    }

    boolean hasPosition = lots.compareTo(BigDecimal.ZERO) > 0;
    position.setPositionLots(lots);
    position.setAddCount(hasPosition ? addCount : 0);
    position.setRealizedPnl(realized.setScale(2, RoundingMode.HALF_UP));

    if (fills.isEmpty()) {
      position.setPositionState("none");
      position.setAvgCost(null);
      position.setEntryPrice(null);
      position.setLastAddPrice(null);
      position.setPeakPrice(null);
      position.setStopPrice(null);
      position.setTotalInvested(BigDecimal.ZERO);
      position.setOpenedAt(null);
      position.setTakeProfitDone(0);
      return;
    }

    if (!hasPosition) {
      position.setPositionState("exited");
      position.setAvgCost(null);
      position.setEntryPrice(null);
      position.setLastAddPrice(null);
      position.setPeakPrice(null);
      position.setStopPrice(null);
      position.setTotalInvested(BigDecimal.ZERO);
      position.setOpenedAt(openedAt);
      position.setTakeProfitDone(0);
      if (!"exited".equals(position.getStatus())) {
        position.setStatus("exited");
      }
      return;
    }

    position.setAvgCost(avg.setScale(2, RoundingMode.HALF_UP));
    position.setEntryPrice(entry);
    if (position.getTargetSellPrice() == null) {
      position.setTargetSellPrice(target);
    }
    position.setLastAddPrice(lastBuyPrice);
    BigDecimal effectivePeak = peak == null ? entry : peak;
    position.setPeakPrice(effectivePeak);
    position.setTotalInvested(avg.multiply(lots)
        .multiply(BigDecimal.valueOf(TechAiPositionEngine.SHARES_PER_LOT)).setScale(2, RoundingMode.HALF_UP));
    position.setOpenedAt(openedAt);
    position.setTakeProfitDone(tpDone ? 1 : 0);
    position.setPositionState(scaled ? "scaled" : "holding");
    if (!"holding".equals(position.getStatus())) {
      position.setStatus("holding");
    }

    BigDecimal atr = isAtrMode(position) ? atrFor(position, position.getStockCode()) : null;
    TechAiPositionEngine.PositionPlan plan = positionEngine.evaluate(
        TechAiPositionEngine.from(position), effectivePeak, atr);
    position.setStopPrice(plan.getStopPrice());
  }

  /** 有效目标价：pos.targetSellPrice 非空时优先；否则 entryPrice × (1 + takeProfitPct/100)。 */
  public BigDecimal effectiveTargetPrice(InvestPositionCommon pos) {
    if (pos == null) {
      return null;
    }
    if (pos.getTargetSellPrice() != null) {
      return pos.getTargetSellPrice();
    }
    return defaultTargetPrice(pos.getEntryPrice(), pos.getTakeProfitPct());
  }

  /** 默认目标价 = entryPrice × (1 + takeProfitPct/100)，结果保留 2 位。 */
  public BigDecimal defaultTargetPrice(BigDecimal entryPrice, BigDecimal takeProfitPct) {
    if (entryPrice == null || takeProfitPct == null
        || entryPrice.compareTo(BigDecimal.ZERO) <= 0
        || takeProfitPct.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    BigDecimal multiplier = BigDecimal.ONE.add(takeProfitPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
    return entryPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
  }

  /** 是否启用 ATR 模式（useAtr == 1）。 */
  public boolean isAtrMode(InvestPositionCommon pos) {
    return pos != null && pos.getUseAtr() != null && pos.getUseAtr() == 1;
  }

  /** 计算指定股票 ATR（period 默认 14）。 */
  public BigDecimal atrFor(InvestPositionCommon pos, String stockCode) {
    int period = pos.getAtrPeriod() == null || pos.getAtrPeriod() <= 0 ? 14 : pos.getAtrPeriod();
    List<TradeStockDaily> recent = dailyRepository.findTop30ByStockCodeOrderByTradeDateDesc(stockCode);
    return atrCalculator.atr(recent, period);
  }
}