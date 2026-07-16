package com.quant.service.potential;

import com.quant.dto.techai.PositionFillDTO;
import com.quant.dto.techai.TechAiAlertDTO;
import com.quant.dto.techai.TechAiPoolItemDTO;
import com.quant.entity.InvestAlert;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.PotentialPool;
import com.quant.entity.PotentialPositionFill;
import com.quant.entity.TradeStockBasic;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.List;

/**
 * 潜力监控 · 无状态工具与 DTO 装配。
 *
 * <p>包含：
 *
 * <ul>
 *   <li>{@code fmt / pctChange}：数值格式化与涨跌幅计算
 *   <li>{@code isTradingTime}：A 股盘中时段判定
 *   <li>{@code parsePositiveDecimal / parsePositiveInteger / parseFlag}：阈值/整数/布尔字段解析
 *   <li>{@code toPoolDTO / toFillDTO / toAlertDTO}：实体 → DTO 转换
 * </ul>
 */
@Component
public class PotentialPoolSupport {

  /** null → "-"，否则去尾零转字符串。 */
  public static String fmt(BigDecimal v) {
    return v == null ? "-" : v.stripTrailingZeros().toPlainString();
  }

  /** 涨跌幅（百分比，2 位小数）。 */
  public static BigDecimal pctChange(BigDecimal value, BigDecimal base) {
    if (value == null || base == null || base.compareTo(BigDecimal.ZERO) == 0) return null;
    return value.subtract(base).divide(base, 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
  }

  /** A 股盘中：9:30–11:30 + 13:00–15:00。 */
  public static boolean isTradingTime() {
    LocalTime now = LocalTime.now();
    return (now.isAfter(LocalTime.of(9, 29)) && now.isBefore(LocalTime.of(11, 31)))
        || (now.isAfter(LocalTime.of(12, 59)) && now.isBefore(LocalTime.of(15, 1)));
  }

  /** 解析正数 BigDecimal（保留 2 位），空值透传 null。 */
  public static BigDecimal parsePositiveDecimal(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      BigDecimal decimal = new BigDecimal(value.trim());
      if (decimal.compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("阈值必须大于 0：" + field);
      }
      return decimal.setScale(2, RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("阈值格式错误：" + field);
    }
  }

  /** 解析正数 Integer，空值透传 null。 */
  public static Integer parsePositiveInteger(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed <= 0) {
        throw new IllegalArgumentException("数值必须大于 0：" + field);
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("数值格式错误：" + field);
    }
  }

  /** 1/true/on/yes → 1，其他 → 0；null → 0。 */
  public static Integer parseFlag(String value) {
    if (value == null) {
      return 0;
    }
    String v = value.trim().toLowerCase();
    return (v.equals("1") || v.equals("true") || v.equals("on") || v.equals("yes")) ? 1 : 0;
  }

  /** 成交记录 → DTO。 */
  public static PositionFillDTO toFillDTO(PotentialPositionFill fill) {
    return PositionFillDTO.builder()
        .id(fill.getId())
        .poolId(fill.getPoolId())
        .stockCode(fill.getStockCode())
        .action(fill.getAction())
        .price(fill.getPrice())
        .lots(fill.getLots())
        .amount(fill.getAmount())
        .fee(fill.getFee())
        .note(fill.getNote())
        .filledAt(fill.getFilledAt())
        .build();
  }

  /** 告警实体 → DTO。 */
  public static TechAiAlertDTO toAlertDTO(InvestAlert alert) {
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

  /**
   * 组装 {@link TechAiPoolItemDTO}。这是一个较复杂的 DTO，需要 positionEngine 评估止损/加仓位 → 留给调用方注入 plan/roadmap。
   *
   * @param pool           监控池条目
   * @param pos            持仓记录（可为 null）
   * @param basic          股票基础（可为 null）
   * @param quote          最新行情快照（可为 null）
   * @param view           {@link com.quant.service.techai.TechAiPositionEngine.PoolView} 视图
   * @param plan           已评估的 {@link com.quant.service.techai.TechAiPositionEngine.PositionPlan}
   * @param roadmap        策略路线图（watching 时预演的全部档位）
   * @param atr            ATR 指标
   * @param targetSellPrice 有效目标价（pos.targetSellPrice 或 entryPrice × (1 + takeProfitPct/100)）
   */
  public static TechAiPoolItemDTO toPoolDTO(
      PotentialPool pool,
      InvestPositionCommon pos,
      TradeStockBasic basic,
      com.quant.entity.TechAiQuoteSnapshot quote,
      com.quant.service.techai.TechAiPositionEngine.PoolView view,
      com.quant.service.techai.TechAiPositionEngine.PositionPlan plan,
      java.util.List<com.quant.dto.techai.StrategyLevelDTO> roadmap,
      BigDecimal atr,
      BigDecimal targetSellPrice) {
    BigDecimal price = quote == null ? null : quote.getLatestPrice();
    BigDecimal dailyChange = quote == null ? null : pctChange(quote.getLatestPrice(), quote.getPrevClosePrice());
    boolean hasPosition = pos != null && pos.getPositionLots() != null && pos.getPositionLots().compareTo(BigDecimal.ZERO) > 0;
    boolean isAtrMode = pos != null && pos.getUseAtr() != null && pos.getUseAtr() == 1;

    return TechAiPoolItemDTO.builder()
        .id(pool.getId())
        .stockCode(pool.getStockCode())
        .qmtCode(com.quant.service.techai.TechAiStockCodeUtils.toQmtCode(pool.getStockCode()))
        .stockName(displayStockName(pool, basic))
        .status(pos != null ? pos.getStatus() : "watching")
        .memo(pool.getMemo())
        .latestPrice(price)
        .dailyChangePct(dailyChange)
        .turnoverRate(quote == null ? null : quote.getTurnoverRate())
        .volume(quote == null ? null : quote.getVolume())
        .quoteTime(quote == null ? null : quote.getQuoteTime())
        .alertMinute1mPct(pos != null ? pos.getAlertMinute1mPct() : null)
        .alertMinute5mPct(pos != null ? pos.getAlertMinute5mPct() : null)
        .alertDailyPct(pos != null ? pos.getAlertDailyPct() : null)
        .alertThreeDayPct(pos != null ? pos.getAlertThreeDayPct() : null)
        .alertTurnoverRatioPct(pos != null ? pos.getAlertTurnoverRatioPct() : null)
        .entryPrice(pos != null ? pos.getEntryPrice() : null)
        .positionLots(pos != null ? pos.getPositionLots() : null)
        .avgCost(pos != null ? pos.getAvgCost() : null)
        .totalInvested(pos != null ? pos.getTotalInvested() : null)
        .addCount(pos != null ? pos.getAddCount() : null)
        .lastAddPrice(pos != null ? pos.getLastAddPrice() : null)
        .peakPrice(pos != null ? pos.getPeakPrice() : null)
        .stopPrice(pos != null ? pos.getStopPrice() : null)
        .realizedPnl(pos != null ? pos.getRealizedPnl() : null)
        .positionState(pos != null ? pos.getPositionState() : null)
        .takeProfitDone(pos != null && pos.getTakeProfitDone() != null && pos.getTakeProfitDone() == 1)
        .openedAt(pos != null ? pos.getOpenedAt() : null)
        .addStepPct(pos != null ? pos.getAddStepPct() : null)
        .trailPct(pos != null ? pos.getTrailPct() : null)
        .addSizeSchedule(pos != null ? pos.getAddSizeSchedule() : null)
        .maxLots(pos != null ? pos.getMaxLots() : null)
        .takeProfitPct(pos != null ? pos.getTakeProfitPct() : null)
        .breakevenAfterTp(pos != null && pos.getBreakevenAfterTp() != null && pos.getBreakevenAfterTp() == 1)
        .timeStopDays(pos != null ? pos.getTimeStopDays() : null)
        .useAtr(pos != null && isAtrMode)
        .atrPeriod(pos != null ? pos.getAtrPeriod() : null)
        .atrAddMult(pos != null ? pos.getAtrAddMult() : null)
        .atrTrailMult(pos != null ? pos.getAtrTrailMult() : null)
        .targetSellPrice(targetSellPrice)
        .nextAddPrice(plan.getNextAddPrice())
        .nextAddLots(plan.getNextAddLots())
        .currentStopPrice(plan.getStopPrice())
        .floatingPnl(plan.getFloatingPnl())
        .floatingPnlPct(plan.getFloatingPnlPct())
        .atrValue(atr)
        .stopBelowCost(plan.isStopBelowCost())
        .pendingSignal(plan.getPendingSignal())
        .roadmap(roadmap)
        .createdAt(pool.getCreatedAt())
        .updatedAt(pool.getUpdatedAt())
        .build();
  }

  /** 显示股票名：优先 DB 中的 basic.stockName，其次 pool.stockName，最后回落到 code。 */
  public static String displayStockName(PotentialPool item, TradeStockBasic basic) {
    if (basic != null && basic.getStockName() != null && !basic.getStockName().isBlank()) {
      return basic.getStockName();
    }
    if (item.getStockName() != null && !item.getStockName().isBlank()) {
      return item.getStockName();
    }
    return item.getStockCode();
  }
}