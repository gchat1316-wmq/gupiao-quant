package com.quant.service.techai;

import com.quant.dto.techai.StrategyLevelDTO;
import com.quant.entity.InvestStockPool;
import com.quant.entity.PotentialPool;
import com.quant.entity.TechAiPool;
import lombok.Builder;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 持仓策略引擎：根据持仓聚合 + 现价 + ATR 计算下一加仓价、移动止损、浮动盈亏与待办信号。
 * 纯计算，不做任何持久化；止损"只升不降"通过 max(已存止损, 计算止损) 实现。
 */
@Component
public class TechAiPositionEngine {

    public static final int SHARES_PER_LOT = 100;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static final String SIGNAL_ADD = "ADD";
    public static final String SIGNAL_STOP = "STOP";
    public static final String SIGNAL_TP = "TP";

    /** 引擎需要的持仓字段视图，解耦具体实体。 */
    @Getter
    @Builder
    public static class PoolView {
        private final BigDecimal positionLots;
        private final BigDecimal avgCost;
        private final Integer useAtr;
        private final BigDecimal entryPrice;
        private final BigDecimal lastAddPrice;
        private final BigDecimal peakPrice;
        private final String addSizeSchedule;
        private final Integer addCount;
        private final BigDecimal maxLots;
        private final BigDecimal atrAddMult;
        private final BigDecimal addStepPct;
        private final BigDecimal atrTrailMult;
        private final BigDecimal trailPct;
        private final BigDecimal stopPrice;
        private final BigDecimal targetSellPrice;
        private final Integer takeProfitDone;
    }

    public static PoolView from(InvestStockPool p) {
        return PoolView.builder()
                .positionLots(p.getPositionLots())
                .avgCost(p.getAvgCost())
                .useAtr(p.getUseAtr())
                .entryPrice(p.getEntryPrice())
                .lastAddPrice(p.getLastAddPrice())
                .peakPrice(p.getPeakPrice())
                .addSizeSchedule(p.getAddSizeSchedule())
                .addCount(p.getAddCount())
                .maxLots(p.getMaxLots())
                .atrAddMult(p.getAtrAddMult())
                .addStepPct(p.getAddStepPct())
                .atrTrailMult(p.getAtrTrailMult())
                .trailPct(p.getTrailPct())
                .stopPrice(p.getStopPrice())
                .targetSellPrice(p.getTargetSellPrice())
                .takeProfitDone(p.getTakeProfitDone())
                .build();
    }

    public static PoolView from(PotentialPool p) {
        return PoolView.builder()
                .positionLots(p.getPositionLots())
                .avgCost(p.getAvgCost())
                .useAtr(p.getUseAtr())
                .entryPrice(p.getEntryPrice())
                .lastAddPrice(p.getLastAddPrice())
                .peakPrice(p.getPeakPrice())
                .addSizeSchedule(p.getAddSizeSchedule())
                .addCount(p.getAddCount())
                .maxLots(p.getMaxLots())
                .atrAddMult(p.getAtrAddMult())
                .addStepPct(p.getAddStepPct())
                .atrTrailMult(p.getAtrTrailMult())
                .trailPct(p.getTrailPct())
                .stopPrice(p.getStopPrice())
                .targetSellPrice(p.getTargetSellPrice())
                .takeProfitDone(p.getTakeProfitDone())
                .build();
    }

    public static PoolView from(TechAiPool p) {
        return PoolView.builder()
                .positionLots(p.getPositionLots())
                .avgCost(p.getAvgCost())
                .useAtr(p.getUseAtr())
                .entryPrice(p.getEntryPrice())
                .lastAddPrice(p.getLastAddPrice())
                .peakPrice(p.getPeakPrice())
                .addSizeSchedule(p.getAddSizeSchedule())
                .addCount(p.getAddCount())
                .maxLots(p.getMaxLots())
                .atrAddMult(p.getAtrAddMult())
                .addStepPct(p.getAddStepPct())
                .atrTrailMult(p.getAtrTrailMult())
                .trailPct(p.getTrailPct())
                .stopPrice(p.getStopPrice())
                .targetSellPrice(p.getTargetSellPrice())
                .takeProfitDone(p.getTakeProfitDone())
                .build();
    }

    @Getter
    @Builder
    public static class PositionPlan {
        private BigDecimal nextAddPrice;
        private BigDecimal nextAddLots;
        private BigDecimal stopPrice;
        private BigDecimal targetPrice;
        private BigDecimal floatingPnl;
        private BigDecimal floatingPnlPct;
        private boolean stopBelowCost;
        /** ADD / STOP / TP / null */
        private String pendingSignal;
    }

    /** 兼容旧调用（InvestStockPool），内部转 PoolView。 */
    public PositionPlan evaluate(InvestStockPool p, BigDecimal price, BigDecimal atr) {
        return evaluate(from(p), price, atr);
    }

    /** 兼容 PotentialPool 调用。 */
    public PositionPlan evaluate(PotentialPool p, BigDecimal price, BigDecimal atr) {
        return evaluate(from(p), price, atr);
    }

    /** 兼容 TechAiPool 调用。 */
    public PositionPlan evaluate(TechAiPool p, BigDecimal price, BigDecimal atr) {
        return evaluate(from(p), price, atr);
    }

    /** 核心计算方法，仅依赖 PoolView。 */
    public PositionPlan evaluate(PoolView p, BigDecimal price, BigDecimal atr) {
        BigDecimal lots = p.getPositionLots();
        boolean hasPosition = lots != null && lots.compareTo(BigDecimal.ZERO) > 0
                && p.getAvgCost() != null;
        if (!hasPosition || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return PositionPlan.builder().build();
        }

        BigDecimal avg = p.getAvgCost();
        boolean useAtr = intFlag(p.getUseAtr()) && atr != null && atr.compareTo(BigDecimal.ZERO) > 0;

        BigDecimal entry = firstNonNull(p.getEntryPrice(), avg);
        BigDecimal lastAdd = firstNonNull(p.getLastAddPrice(), entry);
        BigDecimal effectivePeak = max(p.getPeakPrice(), price, lastAdd, entry);

        // 下一加仓价
        BigDecimal nextAddPrice = null;
        BigDecimal nextAddLots = null;
        List<BigDecimal> schedule = parseSchedule(p.getAddSizeSchedule());
        int nextIndex = 1 + nz(p.getAddCount());
        boolean lotsCapReached = p.getMaxLots() != null && lots.compareTo(p.getMaxLots()) >= 0;
        if (nextIndex < schedule.size() && !lotsCapReached) {
            nextAddLots = schedule.get(nextIndex);
            if (useAtr) {
                nextAddPrice = lastAdd.add(mult(p.getAtrAddMult(), BigDecimal.ONE).multiply(atr));
            } else {
                BigDecimal step = pct(p.getAddStepPct(), BigDecimal.TEN);
                nextAddPrice = lastAdd.multiply(BigDecimal.ONE.add(step)).setScale(2, RoundingMode.HALF_UP);
            }
        }

        // 移动止损：只升不降
        BigDecimal computedStop;
        if (useAtr) {
            computedStop = effectivePeak.subtract(mult(p.getAtrTrailMult(), BigDecimal.valueOf(2)).multiply(atr));
        } else {
            BigDecimal trail = pct(p.getTrailPct(), BigDecimal.TEN);
            computedStop = effectivePeak.multiply(BigDecimal.ONE.subtract(trail));
        }
        computedStop = computedStop.setScale(2, RoundingMode.HALF_UP);
        BigDecimal effectiveStop = p.getStopPrice() == null ? computedStop : p.getStopPrice().max(computedStop);

        // 浮动盈亏
        BigDecimal floatingPnl = price.subtract(avg)
                .multiply(lots).multiply(BigDecimal.valueOf(SHARES_PER_LOT))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal floatingPnlPct = price.subtract(avg)
                .divide(avg, 6, RoundingMode.HALF_UP).multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal target = p.getTargetSellPrice();
        boolean tpDone = intFlag(p.getTakeProfitDone());

        String signal = null;
        if (price.compareTo(effectiveStop) <= 0) {
            signal = SIGNAL_STOP;
        } else if (nextAddPrice != null && price.compareTo(nextAddPrice) >= 0) {
            signal = SIGNAL_ADD;
        } else if (target != null && !tpDone && price.compareTo(target) >= 0) {
            signal = SIGNAL_TP;
        }

        return PositionPlan.builder()
                .nextAddPrice(nextAddPrice)
                .nextAddLots(nextAddLots)
                .stopPrice(effectiveStop)
                .targetPrice(target)
                .floatingPnl(floatingPnl)
                .floatingPnlPct(floatingPnlPct)
                .stopBelowCost(effectiveStop.compareTo(avg) < 0)
                .pendingSignal(signal)
                .build();
    }

    /**
     * 策略路线图预演：给定入场价和参数，计算每一档的买入价/止损/均价。
     * 用于 watching 状态（未建仓）时展示"如果现在入场，计划是怎样的"。
     */
    public List<StrategyLevelDTO> computeRoadmap(BigDecimal entryPrice, PoolView p, BigDecimal atr) {
        List<StrategyLevelDTO> levels = new ArrayList<>();
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return levels;
        }
        List<BigDecimal> schedule = parseSchedule(p.getAddSizeSchedule());
        boolean useAtr = intFlag(p.getUseAtr()) && atr != null && atr.compareTo(BigDecimal.ZERO) > 0;
        BigDecimal step = pct(p.getAddStepPct(), BigDecimal.TEN);
        BigDecimal trail = pct(p.getTrailPct(), BigDecimal.TEN);

        BigDecimal cumulativeLots = BigDecimal.ZERO;
        BigDecimal cumulativeAmount = BigDecimal.ZERO;
        BigDecimal lastBuyPrice = entryPrice;

        for (int i = 0; i < schedule.size(); i++) {
            BigDecimal buyPrice;
            if (i == 0) {
                buyPrice = entryPrice;
            } else {
                if (useAtr) {
                    buyPrice = lastBuyPrice.add(mult(p.getAtrAddMult(), BigDecimal.ONE).multiply(atr));
                } else {
                    buyPrice = lastBuyPrice.multiply(BigDecimal.ONE.add(step)).setScale(2, RoundingMode.HALF_UP);
                }
            }
            BigDecimal lots = schedule.get(i);
            cumulativeLots = cumulativeLots.add(lots);
            cumulativeAmount = cumulativeAmount.add(buyPrice.multiply(lots));
            BigDecimal avgCost = cumulativeAmount.divide(cumulativeLots, 4, RoundingMode.HALF_UP);

            // 止损 = 当前档买入价或更高价 × (1-trail)，因为 peak 至少是 buyPrice
            BigDecimal peak = buyPrice; // 最保守：peak = 当前买入价（后续涨了才更新）
            BigDecimal stop;
            if (useAtr) {
                stop = peak.subtract(mult(p.getAtrTrailMult(), BigDecimal.valueOf(2)).multiply(atr));
            } else {
                stop = peak.multiply(BigDecimal.ONE.subtract(trail));
            }
            stop = stop.setScale(2, RoundingMode.HALF_UP);

            String label = i == 0 ? "首仓" : "加仓" + i;
            levels.add(StrategyLevelDTO.builder()
                    .label(label)
                    .price(buyPrice)
                    .lots(lots)
                    .totalLots(cumulativeLots)
                    .avgCost(avgCost.setScale(2, RoundingMode.HALF_UP))
                    .stopPrice(stop)
                    .stopBelowCost(stop.compareTo(avgCost) < 0)
                    .build());

            lastBuyPrice = buyPrice;
        }
        return levels;
    }

    public List<BigDecimal> parseSchedule(String raw) {
        List<BigDecimal> result = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                String t = part.trim();
                if (t.isEmpty()) {
                    continue;
                }
                try {
                    BigDecimal v = new BigDecimal(t);
                    if (v.compareTo(BigDecimal.ZERO) > 0) {
                        result.add(v);
                    }
                } catch (NumberFormatException ignored) {
                    // 跳过非法档位
                }
            }
        }
        if (result.isEmpty()) {
            result.add(BigDecimal.ONE);
            result.add(BigDecimal.ONE);
            result.add(BigDecimal.ONE);
        }
        return result;
    }

    private BigDecimal pct(BigDecimal value, BigDecimal fallback) {
        BigDecimal v = value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? fallback : value;
        return v.divide(HUNDRED, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal mult(BigDecimal value, BigDecimal fallback) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? fallback : value;
    }

    private boolean intFlag(Integer v) {
        return v != null && v == 1;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private BigDecimal firstNonNull(BigDecimal a, BigDecimal b) {
        return a != null ? a : b;
    }

    private BigDecimal max(BigDecimal... values) {
        BigDecimal m = null;
        for (BigDecimal v : values) {
            if (v == null) {
                continue;
            }
            m = (m == null) ? v : m.max(v);
        }
        return m;
    }
}
