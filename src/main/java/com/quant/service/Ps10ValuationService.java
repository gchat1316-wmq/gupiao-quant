package com.quant.service;

import com.quant.entity.TradeStockFinancial;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 10 倍 PS 统一估值服务。
 *
 * <p>适用于净利润率 ≥ 25% 的高科技公司。
 *
 * <p>估值公式：合理市值 = 未来一年预测营收 × 10
 * <p>评判规则：
 * <ul>
 *   <li>当前市值 &lt; Y1×10 → {@code 低估}</li>
 *   <li>Y1×10 ≤ 当前市值 ≤ Y2×10 → {@code 合理}</li>
 *   <li>当前市值 &gt; Y2×10 → {@code 泡沫}（需 2-3 年营收增长才能支撑）</li>
 * </ul>
 *
 * <p>增速处理：取最新营收同比（限制 15%-50% 区间），无同比或负值则保守估 20%。
 * <p>TTM 营收：最近 4 个正营收季度之和（亏损季不纳入，避免失真）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Ps10ValuationService {

    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final BigDecimal NET_MARGIN_THRESHOLD = BigDecimal.valueOf(25); // 净利率 ≥ 25% 才适用
    private static final double GROWTH_MIN = 0.15;   // 增速下限 15%
    private static final double GROWTH_MAX = 0.50;  // 增速上限 50%
    private static final double GROWTH_DEFAULT = 0.20; // 增速默认值 20%
    private static final BigDecimal YI = BigDecimal.valueOf(1_0000_0000); // 1亿

    private final AStockDataQuoteService aStockDataQuoteService;

    // ── 估值结果 ───────────────────────────────────────

    /**
     * 10 倍 PS 估值结果。
     */
    public record Ps10Result(
            /** 是否适用（净利率 ≥ 25%） */
            boolean applicable,
            /** 估值结论：低估 / 合理 / 泡沫 / — */
            String verdict,
            /** 估值说明 */
            String commentary,
            /** 估值方法 */
            String method,
            /** TTM 营收（亿元） */
            BigDecimal ttmRevenueYi,
            /** 预测 Y1 营收（亿元） */
            BigDecimal revY1Yi,
            /** 预测 Y2 营收（亿元） */
            BigDecimal revY2Yi,
            /** Y1×10 合理市值（亿元） */
            BigDecimal fairCapY1Yi,
            /** Y2×10 合理市值（亿元） */
            BigDecimal fairCapY2Yi,
            /** 净利率（%） */
            BigDecimal netMarginPct,
            /** 使用的增速（%） */
            BigDecimal growthPct,
            /** 当前市值（亿元） */
            BigDecimal currentMarketCapYi
    ) {
        public static Ps10Result inapplicable(String verdict, String commentary, String method) {
            return new Ps10Result(false, verdict, commentary, method,
                    null, null, null, null, null, null, null, null);
        }

        public static Ps10Result inapplicableWithMargin(String verdict, String commentary,
                BigDecimal netMargin, BigDecimal marketCap) {
            return new Ps10Result(false, verdict, commentary, "10 倍 PS 法（不适用）",
                    null, null, null, null, null, netMargin, null, marketCap);
        }

        public static Ps10Result applicable(String verdict, String commentary,
                BigDecimal ttm, BigDecimal y1, BigDecimal y2,
                BigDecimal fair1, BigDecimal fair2,
                BigDecimal netMargin, BigDecimal growth, BigDecimal marketCap) {
            return new Ps10Result(true, verdict, commentary, "10 倍 PS 法",
                    ttm, y1, y2, fair1, fair2, netMargin, growth, marketCap);
        }

        // ── 估值偏离（基于参考年的合理市值） ────────────────────────
        // 偏离参考年：低估/合理 → Y1（合理市值下界），泡沫 → Y2（合理市值上界）。
        // 适用=false 或缺数据时返回 null。

        public BigDecimal deviationPct() { return deviation().pct(); }
        public String deviationRef()   { return deviation().ref(); }
        public String deviationLabel() { return deviation().label(); }

        private record Deviation(BigDecimal pct, String ref, String label) {
            static final Deviation EMPTY = new Deviation(null, null, null);
        }

        private Deviation deviation() {
            if (!applicable || currentMarketCapYi == null) return Deviation.EMPTY;
            BigDecimal ref; String refName;
            if ("泡沫".equals(verdict) && fairCapY2Yi != null && fairCapY2Yi.signum() > 0) {
                ref = fairCapY2Yi; refName = "Y2";
            } else if (fairCapY1Yi != null && fairCapY1Yi.signum() > 0) {
                ref = fairCapY1Yi; refName = "Y1";
            } else {
                return Deviation.EMPTY;
            }
            BigDecimal diff = currentMarketCapYi.subtract(ref);
            BigDecimal pct = diff.divide(ref, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP);
            String label;
            if (pct.signum() < 0) {
                label = String.format(Locale.ROOT, "低于 %s 估值 %.1f%%", refName, pct.abs());
            } else if (pct.signum() > 0) {
                label = String.format(Locale.ROOT, "高于 %s 估值 %.1f%%", refName, pct);
            } else {
                label = String.format(Locale.ROOT, "等于 %s 估值", refName);
            }
            return new Deviation(pct, refName, label);
        }
    }

    // ── 公开接口 ───────────────────────────────────────

    /**
     * 对股票代码做 10 倍 PS 估值（自动拉取实时行情计算市值）。
     */
    public Ps10Result evaluate(String stockCode) {
        return evaluate(stockCode, null);
    }

    /**
     * 对股票代码做 10 倍 PS 估值。
     *
     * @param stockCode         股票代码
     * @param marketCapOverride 若传入则使用此市值（亿元），否则自动拉取实时行情
     */
    public Ps10Result evaluate(String stockCode, BigDecimal marketCapOverride) {
        var quoteMap = aStockDataQuoteService.fetchQuotes(List.of(stockCode));
        var snapshot = quoteMap.values().stream().findFirst().orElse(null);
        BigDecimal marketCap = marketCapOverride;
        if (marketCap == null && snapshot != null) {
            marketCap = snapshot.totalMarketCapYi();
        }
        return evaluateFromMarketCap(marketCap, snapshot != null ? snapshot.latestPrice() : null, stockCode, null);
    }

    /**
     * 核心评估方法（无财务数据时调用）。
     */
    public Ps10Result evaluateFromMarketCap(BigDecimal marketCap, BigDecimal price, String stockCode) {
        return evaluateFromMarketCap(marketCap, price, stockCode, null);
    }

    /**
     * 核心评估方法（带财务数据）。
     *
     * @param marketCap  当前市值（亿元，亿）
     * @param price      当前股价（可 null）
     * @param stockCode  股票代码
     * @param financials 按时间倒序的财务数据列表（可为 null）
     */
    public Ps10Result evaluateFromMarketCap(
            BigDecimal marketCap,
            BigDecimal price,
            String stockCode,
            List<TradeStockFinancial> financials
    ) {
        // 无财务数据 → 不适用
        if (financials == null || financials.isEmpty()) {
            return Ps10Result.inapplicable("—", "缺少财务数据，无法估值", "10 倍 PS 法");
        }

        TradeStockFinancial latest = financials.get(0);
        BigDecimal netMarginPct = latest.getNetMargin();

        // 适用性：净利率 ≥ 25%
        if (netMarginPct == null || netMarginPct.compareTo(NET_MARGIN_THRESHOLD) < 0) {
            String commentary = netMarginPct == null
                    ? "缺少净利率数据，无法估值"
                    : String.format(Locale.ROOT, "净利率 %.2f%%，低于 25%% 基准线，不适用 10 倍 PS 估值", netMarginPct);
            return Ps10Result.inapplicableWithMargin("—", commentary, netMarginPct, marketCap);
        }

        // ── TTM 营收（只累加正营收季度，最多取 8 个历史季）────────────
        BigDecimal ttmRevenueYi; // 单位：亿元
        {
            double posSumYuan = 0;
            int posCnt = 0;
            int limit = Math.min(financials.size(), 8);
            for (int i = 0; i < limit; i++) {
                BigDecimal revBd = financials.get(i).getRevenue(); // 单位：元
                if (revBd != null && revBd.compareTo(BigDecimal.ZERO) > 0) {
                    // 转亿元：除以 1亿
                    BigDecimal revYi = revBd.divide(YI, 10, RoundingMode.HALF_UP);
                    posSumYuan += revYi.doubleValue();
                    posCnt++;
                }
            }
            if (posCnt == 0) {
                return Ps10Result.inapplicable("—", "最近季度营收全为负，无法估值", "10 倍 PS 法");
            }
            // 若不足 4 个季度，按均值外推到 4 季 TTM
            double ttmYi = posCnt >= 4 ? posSumYuan : (posSumYuan / posCnt * 4);
            ttmRevenueYi = BigDecimal.valueOf(ttmYi).setScale(2, RoundingMode.HALF_UP);
        }

        // ── 增速（限制 15%-50%，无/负值取 20%）───────────────────
        BigDecimal yoyBd = latest.getRevenueYoy(); // 数据库存小数（如 0.20=20%）
        double growth;
        if (yoyBd != null && yoyBd.compareTo(BigDecimal.ZERO) > 0) {
            double yoyDecimal = yoyBd.doubleValue(); // 0.20
            // 如果值 > 1，当作百分比形式处理（如 20.0 → 0.20）
            if (yoyDecimal > 1) yoyDecimal = yoyDecimal / 100.0;
            growth = Math.max(GROWTH_MIN, Math.min(GROWTH_MAX, yoyDecimal));
        } else {
            growth = GROWTH_DEFAULT;
        }
        BigDecimal growthPct = roundBd(growth * 100);

        // ── Y1 / Y2 预测（亿元）────────────────────────
        BigDecimal revY1 = roundBd(ttmRevenueYi.doubleValue() * (1 + growth));
        BigDecimal revY2 = roundBd(revY1.doubleValue() * (1 + growth));

        // ── 合理市值（亿元）───────────────────────────
        BigDecimal fairCapY1 = roundBd(revY1.doubleValue() * 10);
        BigDecimal fairCapY2 = roundBd(revY2.doubleValue() * 10);

        // ── 估值判定 ───────────────────────────────────
        String verdict;
        String commentary;
        if (marketCap == null) {
            verdict = "—";
            commentary = "缺少市值数据，无法判定";
        } else if (marketCap.compareTo(fairCapY1) < 0) {
            double discount = fairCapY1.subtract(marketCap)
                    .divide(fairCapY1, 4, RoundingMode.HALF_UP).doubleValue() * 100;
            verdict = "低估";
            commentary = String.format(Locale.ROOT,
                    "当前市值 %.1f 亿 < Y1×10=%.1f 亿，低于合理估值约 %.0f%%，性价比突出",
                    marketCap, fairCapY1, discount);
        } else if (marketCap.compareTo(fairCapY2) > 0) {
            double premium = marketCap.subtract(fairCapY2)
                    .divide(fairCapY2, 4, RoundingMode.HALF_UP).doubleValue() * 100;
            verdict = "泡沫";
            commentary = String.format(Locale.ROOT,
                    "当前市值 %.1f 亿 > Y2×10=%.1f 亿，需 %.0f%% 的营收增长才能支撑，已透支未来",
                    marketCap, fairCapY2, premium);
        } else {
            double premium = marketCap.subtract(fairCapY1)
                    .divide(fairCapY1, 4, RoundingMode.HALF_UP).doubleValue() * 100;
            verdict = "合理";
            commentary = String.format(Locale.ROOT,
                    "当前市值 %.1f 亿在 Y1×10=%.1f 亿至 Y2×10=%.1f 亿区间，透支约 %.0f%%",
                    marketCap, fairCapY1, fairCapY2, premium);
        }

        return Ps10Result.applicable(verdict, commentary,
                ttmRevenueYi, revY1, revY2,
                fairCapY1, fairCapY2,
                netMarginPct, growthPct, marketCap);
    }

    private BigDecimal roundBd(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP);
    }
}
