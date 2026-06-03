package com.quant.service.prosperitystrong;

import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockFinancialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Step 3: 16 季度财务硬筛
 *
 * 规则(任一不符直接淘汰):
 *   R1. 营收同比近 4 季 >= 20%
 *   R2. 扣非净利润同比近 4 季 >= 0%
 *   R3. 近 4 季毛利率均值 >= 25%
 *   R4. 资产负债率 <= 70%
 *   R5. 近 4 季经营现金流累计 > 0
 *   R6. 最新 ROE >= 10%
 *
 * 输出 0-100 的财务评分(达标率)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinancialHardFilter {

    private static final int FIN_QUARTERS = 16;
    private static final int RECENT_4Q = 4;

    private final TradeStockFinancialRepository repo;

    public Result evaluate(String stockCode) {
        List<TradeStockFinancial> records = repo.findByStockCodeOrderByReportDateDesc(stockCode);
        if (records.isEmpty()) {
            return Result.fail(stockCode, "无财务数据");
        }
        List<TradeStockFinancial> recent = records.size() > FIN_QUARTERS
                ? records.subList(0, FIN_QUARTERS) : records;
        List<TradeStockFinancial> last4 = records.size() > RECENT_4Q
                ? records.subList(0, RECENT_4Q) : records;

        int pass = 0;
        int total = 6;
        StringBuilder reason = new StringBuilder();

        // R1
        boolean r1 = allMeet(last4, TradeStockFinancial::getRevenueYoy, BigDecimal.valueOf(20));
        if (r1) pass++; else reason.append("营收同比近4季<20%; ");

        // R2
        boolean r2 = allMeet(last4, TradeStockFinancial::getDeductedNetProfitYoy, BigDecimal.ZERO);
        if (r2) pass++; else reason.append("扣非同比近4季有<0; ");

        // R3
        BigDecimal avgGross = average(last4, TradeStockFinancial::getGrossMargin);
        boolean r3 = avgGross != null && avgGross.compareTo(BigDecimal.valueOf(25)) >= 0;
        if (r3) pass++; else reason.append("毛利率均值<25%; ");

        // R4
        TradeStockFinancial latest = recent.get(0);
        boolean r4 = latest.getDebtRatio() == null
                || latest.getDebtRatio().compareTo(BigDecimal.valueOf(70)) <= 0;
        if (r4) pass++; else reason.append("负债率>70%; ");

        // R5
        BigDecimal sumOcf = sum(last4, TradeStockFinancial::getOperatingCashflow);
        boolean r5 = sumOcf != null && sumOcf.compareTo(BigDecimal.ZERO) > 0;
        if (r5) pass++; else reason.append("近4季经营现金流累计<=0; ");

        // R6
        boolean r6 = latest.getRoe() != null
                && latest.getRoe().compareTo(BigDecimal.valueOf(10)) >= 0;
        if (r6) pass++; else reason.append("最新ROE<10%; ");

        BigDecimal score = BigDecimal.valueOf(pass * 100.0 / total)
                .setScale(2, RoundingMode.HALF_UP);
        boolean hardPassed = pass == total; // PRD: 任一不符直接淘汰
        return new Result(stockCode, score, hardPassed,
                reason.length() == 0 ? "全部达标" : reason.toString().trim(),
                avgNetMargin(last4));
    }

    private boolean allMeet(List<TradeStockFinancial> list,
                            java.util.function.Function<TradeStockFinancial, BigDecimal> getter,
                            BigDecimal threshold) {
        if (list.isEmpty()) return false;
        for (TradeStockFinancial f : list) {
            BigDecimal v = getter.apply(f);
            if (v == null) return false;
            if (v.compareTo(threshold) < 0) return false;
        }
        return true;
    }

    private BigDecimal average(List<TradeStockFinancial> list,
                                java.util.function.Function<TradeStockFinancial, BigDecimal> getter) {
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (TradeStockFinancial f : list) {
            BigDecimal v = getter.apply(f);
            if (v != null) {
                sum = sum.add(v);
                n++;
            }
        }
        if (n == 0) return null;
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<TradeStockFinancial> list,
                            java.util.function.Function<TradeStockFinancial, BigDecimal> getter) {
        BigDecimal sum = BigDecimal.ZERO;
        boolean any = false;
        for (TradeStockFinancial f : list) {
            BigDecimal v = getter.apply(f);
            if (v != null) {
                sum = sum.add(v);
                any = true;
            }
        }
        return any ? sum : null;
    }

    private BigDecimal avgNetMargin(List<TradeStockFinancial> list) {
        return average(list, TradeStockFinancial::getNetMargin);
    }

    public record Result(
            String stockCode,
            BigDecimal financeScore,
            boolean hardPassed,
            String reason,
            BigDecimal netMarginAvg4q) {
        public static Result fail(String code, String why) {
            return new Result(code, BigDecimal.ZERO, false, why, null);
        }
    }
}
