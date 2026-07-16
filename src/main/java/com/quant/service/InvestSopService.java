package com.quant.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.quant.dto.invest.SopCheckupDTO;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockFinancialRepository;

import lombok.RequiredArgsConstructor;

/**
 * 实战选股 SOP · 三大数字体检：毛利率趋势、营收同比、扣非净利润同比，并给出综合结论。
 *
 * <p>股票解析复用 {@link InvestPoolService#resolveStock}；同比/季度/景气度计算复用 {@link InvestMathUtils}。 缓存/事务边界由
 * {@link InvestService} 门面持有。
 */
@Service
@RequiredArgsConstructor
public class InvestSopService {

  private static final int SOP_QUARTERS = 8;

  private final TradeStockFinancialRepository financialRepository;
  private final InvestPoolService poolService;

  SopCheckupDTO sopCheckup(String keyword) {
    Optional<TradeStockBasic> infoOpt =
        poolService.resolveStock(keyword == null ? "" : keyword.trim());
    if (infoOpt.isEmpty()) {
      return SopCheckupDTO.builder()
          .matched(false)
          .message("未找到股票：" + keyword + "（请输入6位代码或完整名称）")
          .build();
    }
    TradeStockBasic info = infoOpt.get();
    List<TradeStockFinancial> all =
        financialRepository.findByStockCodeOrderByReportDateDesc(info.getStockCode());
    if (all.isEmpty()) {
      return SopCheckupDTO.builder()
          .matched(false)
          .stockCode(info.getStockCode())
          .stockName(info.getStockName())
          .message("暂无该股票的财务数据")
          .build();
    }
    Map<LocalDate, TradeStockFinancial> dateMap =
        all.stream()
            .collect(Collectors.toMap(TradeStockFinancial::getReportDate, r -> r, (a, b) -> a));
    List<TradeStockFinancial> asc =
        new ArrayList<>(all.stream().limit(SOP_QUARTERS).collect(Collectors.toList()));
    Collections.reverse(asc);

    SopCheckupDTO.MetricCheck gross = buildGrossMargin(asc);
    SopCheckupDTO.MetricCheck rev = buildRevenueYoy(asc, dateMap);
    SopCheckupDTO.MetricCheck profit = buildProfitYoy(asc, dateMap, rev.getLatest());

    String overall = combineVerdict(gross.getVerdict(), rev.getVerdict(), profit.getVerdict());
    String summary =
        switch (overall) {
          case "pass" -> "三大数字全部通过 ✓ 投资逻辑被财报印证，可重点跟踪";
          case "warn" -> "部分指标偏弱 ⚠ 建议再观察 1-2 个季度";
          default -> "数字不漂亮 ✗ 谨慎对待，可能存在基本面瑕疵";
        };

    return SopCheckupDTO.builder()
        .matched(true)
        .stockCode(info.getStockCode())
        .stockName(info.getStockName())
        .grossMargin(gross)
        .revenueYoy(rev)
        .profitYoy(profit)
        .overallVerdict(overall)
        .overallSummary(summary)
        .build();
  }

  private SopCheckupDTO.MetricCheck buildGrossMargin(List<TradeStockFinancial> asc) {
    List<SopCheckupDTO.QuarterPoint> series = new ArrayList<>();
    BigDecimal latest = null;
    BigDecimal first = null;
    for (TradeStockFinancial f : asc) {
      BigDecimal gm = f.getGrossMargin();
      series.add(
          SopCheckupDTO.QuarterPoint.builder()
              .quarter(InvestMathUtils.formatQuarter(f.getReportDate()))
              .value(gm)
              .build());
      if (gm != null) {
        if (first == null) first = gm;
        latest = gm;
      }
    }
    String verdict;
    String tip;
    if (latest == null || first == null) {
      verdict = "warn";
      tip = "缺少毛利率数据";
    } else {
      double d = latest.subtract(first).doubleValue();
      if (d >= 0.5) {
        verdict = "pass";
        tip =
            String.format("毛利率从 %.1f%% 提升到 %.1f%%，定价权强", first.doubleValue(), latest.doubleValue());
      } else if (d >= -1.0) {
        verdict = "pass";
        tip = String.format("毛利率稳定在 %.1f%% 附近，护城河稳固", latest.doubleValue());
      } else if (d >= -3.0) {
        verdict = "warn";
        tip = String.format("毛利率下滑 %.1f 个百分点，需关注是否价格战", -d);
      } else {
        verdict = "fail";
        tip = String.format("毛利率大幅下滑 %.1f 个百分点，护城河可能被侵蚀", -d);
      }
    }
    return SopCheckupDTO.MetricCheck.builder()
        .label("毛利率")
        .unit("%")
        .series(series)
        .latest(latest)
        .verdict(verdict)
        .tip(tip)
        .build();
  }

  private SopCheckupDTO.MetricCheck buildRevenueYoy(
      List<TradeStockFinancial> asc, Map<LocalDate, TradeStockFinancial> dateMap) {
    List<SopCheckupDTO.QuarterPoint> series = new ArrayList<>();
    BigDecimal latest = null;
    int highCount = 0, valid = 0;
    for (TradeStockFinancial f : asc) {
      BigDecimal yoy = f.getRevenueYoy();
      if (yoy == null) {
        TradeStockFinancial prev = dateMap.get(f.getReportDate().minusYears(1));
        yoy = InvestMathUtils.calcYoy(f.getRevenue(), prev != null ? prev.getRevenue() : null);
      }
      series.add(
          SopCheckupDTO.QuarterPoint.builder()
              .quarter(InvestMathUtils.formatQuarter(f.getReportDate()))
              .value(yoy)
              .build());
      if (yoy != null) {
        valid++;
        latest = yoy;
        if (yoy.doubleValue() >= 20) highCount++;
      }
    }
    String verdict, tip;
    if (valid == 0 || latest == null) {
      verdict = "warn";
      tip = "缺少营收同比数据";
    } else {
      double ratio = highCount * 1.0 / valid;
      if (latest.doubleValue() >= 20 && ratio >= 0.6) {
        verdict = "pass";
        tip =
            String.format(
                "最新营收 +%.1f%%，%d/%d 个季度 ≥ 20%%，持续高增长", latest.doubleValue(), highCount, valid);
      } else if (latest.doubleValue() >= 10) {
        verdict = "warn";
        tip = String.format("最新营收 +%.1f%%，未达 20%% 高增长线", latest.doubleValue());
      } else {
        verdict = "fail";
        tip = String.format("最新营收 %.1f%%，增长乏力", latest.doubleValue());
      }
    }
    return SopCheckupDTO.MetricCheck.builder()
        .label("营收同比")
        .unit("%")
        .series(series)
        .latest(latest)
        .verdict(verdict)
        .tip(tip)
        .build();
  }

  private SopCheckupDTO.MetricCheck buildProfitYoy(
      List<TradeStockFinancial> asc,
      Map<LocalDate, TradeStockFinancial> dateMap,
      BigDecimal latestRevenueYoy) {
    List<SopCheckupDTO.QuarterPoint> series = new ArrayList<>();
    BigDecimal latest = null;
    for (TradeStockFinancial f : asc) {
      BigDecimal py = f.getDeductedNetProfitYoy();
      if (py == null) {
        TradeStockFinancial prev = dateMap.get(f.getReportDate().minusYears(1));
        py = InvestMathUtils.calcYoy(f.getNetProfit(), prev != null ? prev.getNetProfit() : null);
      }
      series.add(
          SopCheckupDTO.QuarterPoint.builder()
              .quarter(InvestMathUtils.formatQuarter(f.getReportDate()))
              .value(py)
              .build());
      if (py != null) latest = py;
    }
    String verdict, tip;
    if (latest == null) {
      verdict = "warn";
      tip = "缺少扣非净利润同比数据";
    } else if (latestRevenueYoy == null) {
      verdict = latest.doubleValue() >= 20 ? "pass" : (latest.doubleValue() >= 0 ? "warn" : "fail");
      tip = String.format("最新扣非 %+.1f%%", latest.doubleValue());
    } else {
      double diff = latest.doubleValue() - latestRevenueYoy.doubleValue();
      if (diff >= 5 && latest.doubleValue() >= 0) {
        verdict = "pass";
        tip =
            String.format(
                "扣非 +%.1f%% > 营收 +%.1f%%，规模效应显著",
                latest.doubleValue(), latestRevenueYoy.doubleValue());
      } else if (latest.doubleValue() >= 0 && diff >= -5) {
        verdict = "warn";
        tip =
            String.format(
                "扣非 %+.1f%% 与营收 %+.1f%% 基本同步，盈利能力未提升",
                latest.doubleValue(), latestRevenueYoy.doubleValue());
      } else {
        verdict = "fail";
        tip =
            String.format(
                "扣非 %+.1f%% 落后营收 %+.1f%%，规模不经济",
                latest.doubleValue(), latestRevenueYoy.doubleValue());
      }
    }
    return SopCheckupDTO.MetricCheck.builder()
        .label("扣非净利润同比")
        .unit("%")
        .series(series)
        .latest(latest)
        .verdict(verdict)
        .tip(tip)
        .build();
  }

  private String combineVerdict(String... vs) {
    int pass = 0, warn = 0, fail = 0;
    for (String v : vs) {
      if ("pass".equals(v)) pass++;
      else if ("warn".equals(v)) warn++;
      else if ("fail".equals(v)) fail++;
    }
    if (fail >= 1) return "fail";
    if (pass == vs.length) return "pass";
    if (warn >= 2) return "fail";
    return "warn";
  }
}
