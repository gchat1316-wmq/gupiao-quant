package com.quant.service.practical;

import static com.quant.service.practical.PracticalSelectSupport.round2;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.quant.dto.invest.SopCheckupDTO;
import com.quant.dto.practicalselect.FinancialAnalysis;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.InvestService;

import lombok.RequiredArgsConstructor;

/** 实战选股 · 财务分析（16 季度快照 + SOP 体检 + 业绩拐点）。 */
@Component
@RequiredArgsConstructor
public class PracticalFinancialAnalyzer {

  private static final int QUARTER_COUNT = 16; // 16 季度财务数据

  private final TradeStockFinancialRepository financialRepository;
  private final InvestService investService;

  public FinancialAnalysis buildFinancials(String code) {
    List<TradeStockFinancial> all = financialRepository.findByStockCodeOrderByReportDateDesc(code);
    if (all.isEmpty()) {
      return FinancialAnalysis.builder()
          .summary("暂无该股票财务数据")
          .quarters(List.of())
          .sopVerdict("warn")
          .sopSummary("缺少财务数据，无法做 SOP 判定")
          .turnaroundDetected(false)
          .turnaroundNote("无数据")
          .build();
    }

    // 取最近 16 季度（按报告期升序）
    List<TradeStockFinancial> recent =
        new ArrayList<>(all.subList(0, Math.min(QUARTER_COUNT, all.size())));
    java.util.Collections.reverse(recent);

    List<FinancialAnalysis.QuarterSnapshot> snaps =
        recent.stream().map(this::toQuarterSnapshot).collect(Collectors.toList());

    // SOP 体检（复用现成实现），同时拆出三项明细给前端
    SopCheckupDTO sop = investService.sopCheckup(code);
    String sopVerdict = sop != null && sop.isMatched() ? sop.getOverallVerdict() : "warn";
    String sopSummary = sop != null && sop.isMatched() ? sop.getOverallSummary() : "缺少数据";
    List<FinancialAnalysis.SopMetricBrief> sopMetrics = new ArrayList<>();
    if (sop != null && sop.isMatched()) {
      if (sop.getGrossMargin() != null) sopMetrics.add(toSopBrief(sop.getGrossMargin()));
      if (sop.getRevenueYoy() != null) sopMetrics.add(toSopBrief(sop.getRevenueYoy()));
      if (sop.getProfitYoy() != null) sopMetrics.add(toSopBrief(sop.getProfitYoy()));
    }

    // 趋势序列（最近 8 季度）
    int n = Math.min(8, recent.size());
    List<Double> revYoy = new ArrayList<>();
    List<Double> profitYoy = new ArrayList<>();
    List<Double> gm = new ArrayList<>();
    for (int i = recent.size() - n; i < recent.size(); i++) {
      TradeStockFinancial f = recent.get(i);
      revYoy.add(f.getRevenueYoy() != null ? round2(f.getRevenueYoy().doubleValue()) : null);
      profitYoy.add(
          f.getDeductedNetProfitYoy() != null
              ? round2(f.getDeductedNetProfitYoy().doubleValue())
              : null);
      gm.add(f.getGrossMargin() != null ? round2(f.getGrossMargin().doubleValue()) : null);
    }

    // 最新一期
    TradeStockFinancial latest = recent.get(recent.size() - 1);
    Double latestGm =
        latest.getGrossMargin() != null ? round2(latest.getGrossMargin().doubleValue()) : null;
    Double latestNm =
        latest.getNetMargin() != null ? round2(latest.getNetMargin().doubleValue()) : null;
    Double latestRevYoy =
        latest.getRevenueYoy() != null ? round2(latest.getRevenueYoy().doubleValue()) : null;
    Double latestProfitYoy =
        latest.getDeductedNetProfitYoy() != null
            ? round2(latest.getDeductedNetProfitYoy().doubleValue())
            : null;

    // 业绩复苏判定：上一期营收同比 < 0 且最新一期 > 0
    boolean turnaround = false;
    String turnaroundNote = null;
    if (recent.size() >= 2) {
      TradeStockFinancial prev = recent.get(recent.size() - 2);
      BigDecimal prevYoy = prev.getRevenueYoy();
      if (prevYoy != null
          && prevYoy.doubleValue() < 0
          && latestRevYoy != null
          && latestRevYoy > 0) {
        turnaround = true;
        turnaroundNote =
            String.format(
                Locale.ROOT,
                "营收同比由上一期的 %.2f%% 转正为最新 +%.2f%%，业绩拐点确认",
                prevYoy.doubleValue(),
                latestRevYoy);
      }
    }

    String summary =
        buildFinancialsSummary(snaps, sopVerdict, turnaround, latestProfitYoy, latestRevYoy);

    return FinancialAnalysis.builder()
        .summary(summary)
        .quarters(snaps)
        .sopVerdict(sopVerdict)
        .sopSummary(sopSummary)
        .sopMetrics(sopMetrics)
        .revenueYoySeries(revYoy)
        .profitYoySeries(profitYoy)
        .grossMarginSeries(gm)
        .latestGrossMargin(latestGm)
        .latestNetMargin(latestNm)
        .latestRevenueYoy(latestRevYoy)
        .latestProfitYoy(latestProfitYoy)
        .turnaroundDetected(turnaround)
        .turnaroundNote(turnaroundNote)
        .build();
  }

  private FinancialAnalysis.SopMetricBrief toSopBrief(SopCheckupDTO.MetricCheck m) {
    String unit = m.getUnit() == null ? "" : m.getUnit();
    String latestText =
        m.getLatest() == null
            ? "—"
            : (m.getLatest().doubleValue() >= 0 ? "+" : "")
                + m.getLatest().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                + unit;
    return FinancialAnalysis.SopMetricBrief.builder()
        .label(m.getLabel())
        .verdict(m.getVerdict())
        .latestText(latestText)
        .tip(m.getTip())
        .build();
  }

  private FinancialAnalysis.QuarterSnapshot toQuarterSnapshot(TradeStockFinancial f) {
    // revenue 单位是元，转亿元
    Double revYi =
        f.getRevenue() != null ? round2(f.getRevenue().doubleValue() / 1_0000_0000) : null;
    return FinancialAnalysis.QuarterSnapshot.builder()
        .quarter(formatQuarter(f.getReportDate()))
        .reportDate(f.getReportDate().toString())
        .revenueYi(revYi)
        .revenueYoy(f.getRevenueYoy() != null ? round2(f.getRevenueYoy().doubleValue()) : null)
        .netMargin(f.getNetMargin() != null ? round2(f.getNetMargin().doubleValue()) : null)
        .grossMargin(f.getGrossMargin() != null ? round2(f.getGrossMargin().doubleValue()) : null)
        .eps(f.getEps() != null ? round2(f.getEps().doubleValue()) : null)
        .roe(f.getRoe() != null ? round2(f.getRoe().doubleValue()) : null)
        .build();
  }

  private String buildFinancialsSummary(
      List<FinancialAnalysis.QuarterSnapshot> snaps,
      String sopVerdict,
      boolean turnaround,
      Double profitYoy,
      Double revYoy) {
    StringBuilder sb = new StringBuilder();
    sb.append("最近 ").append(snaps.size()).append(" 个季度财务数据：");
    if (turnaround) {
      sb.append("营收同比刚刚转正");
    } else if (revYoy != null && revYoy > 0) {
      sb.append(String.format(Locale.ROOT, "最近营收 +%.2f%%", revYoy));
    } else if (revYoy != null) {
      sb.append(String.format(Locale.ROOT, "最近营收 %.2f%%", revYoy));
    }
    if (profitYoy != null && revYoy != null) {
      double diff = profitYoy - revYoy;
      sb.append(String.format(Locale.ROOT, "；扣非同比 %+.2f%%，与营收增速差 %+.2f%%。", profitYoy, diff));
    } else {
      sb.append("。");
    }
    sb.append("SOP 体检：");
    switch (sopVerdict) {
      case "pass" -> sb.append("✓ PASS");
      case "warn" -> sb.append("⚠ WARN");
      case "fail" -> sb.append("✗ FAIL");
      default -> sb.append("—");
    }
    return sb.toString();
  }

  private String formatQuarter(LocalDate d) {
    int yy = d.getYear() % 100;
    int q =
        switch (d.getMonthValue()) {
          case 3 -> 1;
          case 6 -> 2;
          case 9 -> 3;
          case 12 -> 4;
          default -> (d.getMonthValue() - 1) / 3 + 1;
        };
    return String.format("%02dQ%d", yy, q);
  }
}
