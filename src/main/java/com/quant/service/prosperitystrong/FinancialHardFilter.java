package com.quant.service.prosperitystrong;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Component;

import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockFinancialRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Step 3: 3 规则财务硬筛
 *
 * <p>规则(任一不符直接淘汰): R1. 营收同比近 3 季全部 > 0 R2. 扣非净利润同比近 3 季全部 > 0 R3. 最近一季营收同比 > 20%
 *
 * <p>输出 0-100 的财务评分(达标率)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinancialHardFilter {

  private static final int FIN_QUARTERS = 16;
  private static final int RECENT_3Q = 3;

  private final TradeStockFinancialRepository repo;

  public Result evaluate(String stockCode) {
    List<TradeStockFinancial> records = repo.findByStockCodeOrderByReportDateDesc(stockCode);
    if (records.isEmpty()) {
      return Result.fail(stockCode, "无财务数据");
    }
    List<TradeStockFinancial> last3 =
        records.size() > RECENT_3Q ? records.subList(0, RECENT_3Q) : records;

    int pass = 0;
    int total = 3;
    StringBuilder reason = new StringBuilder();

    // R1: 营收同比近3季全部 > 0
    BigDecimal revenueYoyMin3q = min(last3, TradeStockFinancial::getRevenueYoy);
    boolean r1 = revenueYoyMin3q != null && revenueYoyMin3q.compareTo(BigDecimal.ZERO) > 0;
    if (r1) pass++;
    else reason.append("营收同比近3季:").append(pct(revenueYoyMin3q)).append("≤0%; ");

    // R2: 扣非同比近3季全部 > 0
    BigDecimal deductedYoyMin3q = min(last3, TradeStockFinancial::getDeductedNetProfitYoy);
    boolean r2 = deductedYoyMin3q != null && deductedYoyMin3q.compareTo(BigDecimal.ZERO) > 0;
    if (r2) pass++;
    else reason.append("扣非同比近3季:").append(pct(deductedYoyMin3q)).append("≤0%; ");

    // R3: 最近一季营收同比 > 20%
    TradeStockFinancial latest = records.get(0);
    BigDecimal latestRevenueYoy = latest.getRevenueYoy();
    boolean r3 = latestRevenueYoy != null && latestRevenueYoy.compareTo(BigDecimal.valueOf(20)) > 0;
    if (r3) pass++;
    else reason.append("最近季度营收增速:").append(pct(latestRevenueYoy)).append("≤20%; ");

    BigDecimal score = BigDecimal.valueOf(pass * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
    boolean hardPassed = pass == total;
    return new Result(
        stockCode,
        score,
        hardPassed,
        reason.length() == 0 ? "全部达标" : reason.toString().trim(),
        revenueYoyMin3q,
        deductedYoyMin3q);
  }

  private boolean allMeet(
      List<TradeStockFinancial> list,
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

  private BigDecimal average(
      List<TradeStockFinancial> list,
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

  private BigDecimal sum(
      List<TradeStockFinancial> list,
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

  private BigDecimal min(
      List<TradeStockFinancial> list,
      java.util.function.Function<TradeStockFinancial, BigDecimal> getter) {
    if (list.isEmpty()) return null;
    BigDecimal min = null;
    for (TradeStockFinancial f : list) {
      BigDecimal v = getter.apply(f);
      if (v == null) return null;
      if (min == null || v.compareTo(min) < 0) {
        min = v;
      }
    }
    return min;
  }

  private String pct(BigDecimal v) {
    if (v == null) return "--";
    return v.setScale(2, RoundingMode.HALF_UP).toString() + "%";
  }

  private String yi(BigDecimal v) {
    if (v == null) return "--";
    return v.divide(BigDecimal.valueOf(1e8), 2, RoundingMode.HALF_UP).toString() + "亿";
  }

  private BigDecimal avgNetMargin(List<TradeStockFinancial> list) {
    return average(list, TradeStockFinancial::getNetMargin);
  }

  /**
   * @param revenueYoyMin3q 营收同比近3季最小值（用于前端展示）
   * @param latestRevenueYoy 最近一季营收同比（用于前端展示）
   */
  public record Result(
      String stockCode,
      BigDecimal financeScore,
      boolean hardPassed,
      String reason,
      BigDecimal revenueYoyMin3q,
      BigDecimal deductedNetProfitYoyMin3q) {
    public static Result fail(String code, String why) {
      return new Result(code, BigDecimal.ZERO, false, why, null, null);
    }
  }
}
