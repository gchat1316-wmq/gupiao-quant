package com.quant.service.practical;

import static com.quant.service.practical.PracticalSelectSupport.round2;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.quant.dto.practicalselect.FinancialAnalysis;
import com.quant.dto.practicalselect.ValuationAnalysis;
import com.quant.entity.TradeStockBasic;
import com.quant.service.AStockDataQuoteService;

/** 实战选股 · 估值分析（10 倍 PS 法 + 预测营收 + 低估/合理/泡沫判定）。 */
@Component
public class PracticalValuationAnalyzer {

  public ValuationAnalysis buildValuation(
      TradeStockBasic basic, AStockDataQuoteService.QuoteSnapshot quote, FinancialAnalysis fin) {
    Double currentPrice =
        quote != null && quote.latestPrice() != null ? quote.latestPrice().doubleValue() : null;
    Double totalSharesYi =
        basic.getTotalShares() != null
            ? round2(basic.getTotalShares().doubleValue() / 1_0000_0000)
            : null;
    Double latestNm = fin != null ? fin.getLatestNetMargin() : null;

    // 市值（优先用 quote 里的 totalMarketCapYi）
    Double marketCap = null;
    if (quote != null && quote.totalMarketCapYi() != null) {
      marketCap = round2(quote.totalMarketCapYi().doubleValue());
    } else if (currentPrice != null && totalSharesYi != null) {
      marketCap = round2(currentPrice * totalSharesYi);
    }

    // 统一 10 倍 PS 法（适用于净利润率 ≥ 25% 的高科技公司）
    final double PS = 10.0;
    String method = "10 倍 PS 法";
    String methodReason;
    if (latestNm == null) {
      methodReason = "缺少净利率数据，按 25% 高科技公司默认值给 10 倍 PS（仅供参考）";
    } else if (latestNm >= 25) {
      methodReason = String.format(Locale.ROOT, "净利率 %.2f%% ≥ 25%%，适用 10 倍 PS 估值法", latestNm);
    } else {
      methodReason =
          String.format(Locale.ROOT, "净利率 %.2f%%，低于 25%% 基准线，10 倍 PS 仅供参考，需结合其他方法综合判断", latestNm);
    }

    // 预测营收：Y0 = 最近 TTM（只累加正数季度），Y1 = Y0 × 增速（用最新营收同比或保守 20%），Y2 = Y1 × 增速
    Double revY0 = null, revY1 = null, revY2 = null;
    if (fin != null && fin.getQuarters() != null && !fin.getQuarters().isEmpty()) {
      int sz = fin.getQuarters().size();
      // 只用正数季度累加（亏损季单季负值会导致 TTM 失真）
      double posSum = 0;
      int posCnt = 0;
      for (int i = Math.max(0, sz - 4); i < sz; i++) {
        Double r = fin.getQuarters().get(i).getRevenueYi();
        if (r != null && r > 0) {
          posSum += r;
          posCnt++;
        }
      }
      if (posCnt == 4) {
        revY0 = round2(posSum);
      } else if (posCnt > 0) {
        // 数据不全的兜底：按已有正数季均值外推到 4 个季度
        revY0 = round2(posSum / posCnt * 4);
      } else {
        // 全部负或缺失：取最近一期正值（取绝对值）×4
        for (int i = sz - 1; i >= 0; i--) {
          Double r = fin.getQuarters().get(i).getRevenueYi();
          if (r != null && r > 0) {
            revY0 = round2(r * 4);
            break;
          }
        }
      }

      Double yoy = fin.getLatestRevenueYoy();
      // 增速：最新营收同比为正则采用，但夹在 15%-50% 之间；为负或缺失则按 20% 保守估
      double growth;
      if (yoy != null && yoy > 0) {
        growth = Math.max(0.15, Math.min(0.50, yoy / 100));
      } else {
        growth = 0.20;
      }
      if (revY0 != null && revY0 > 0) {
        revY1 = round2(revY0 * (1 + growth));
        revY2 = round2(revY1 * (1 + growth));
      }
    }

    Double fairCapY1 = (revY1 != null) ? round2(revY1 * PS) : null;
    Double fairCapY2 = (revY2 != null) ? round2(revY2 * PS) : null;

    String verdict;
    String commentary;
    if (marketCap == null) {
      verdict = "—";
      commentary = "缺少市值数据，无法判定估值水平";
    } else if (fairCapY1 == null) {
      verdict = "—";
      commentary = "缺少预测营收，无法判定";
    } else if (marketCap < fairCapY1) {
      double discount = (fairCapY1 - marketCap) / fairCapY1 * 100;
      verdict = "低估";
      commentary =
          String.format(
              Locale.ROOT,
              "当前市值 %.1f 亿 < Y1×10=%.1f 亿，低于合理估值约 %.0f%%，性价比突出",
              marketCap,
              fairCapY1,
              discount);
    } else if (fairCapY2 != null && marketCap > fairCapY2) {
      double premium = (marketCap - fairCapY2) / fairCapY2 * 100;
      verdict = "泡沫";
      commentary =
          String.format(
              Locale.ROOT,
              "当前市值 %.1f 亿 > Y2×10=%.1f 亿，需 %.0f%% 的营收增长才能支撑，已透支未来",
              marketCap,
              fairCapY2,
              premium);
    } else {
      verdict = "合理";
      double premium = (marketCap - fairCapY1) / fairCapY1 * 100;
      commentary =
          String.format(
              Locale.ROOT,
              "当前市值 %.1f 亿在 Y1×10=%.1f 亿至 Y2×10=%.1f 亿区间，透支约 %.0f%%",
              marketCap,
              fairCapY1,
              fairCapY2 != null ? fairCapY2 : 0,
              premium);
    }

    String buildTip = null;
    if (verdict.equals("低估") || verdict.equals("合理")) {
      buildTip = "可考虑以最近大阳线起涨点为参考，逢回踩分批建仓观察仓";
    } else if (verdict.equals("泡沫")) {
      buildTip = "估值已透支，建议等回踩至合理区间再考虑";
    }

    return ValuationAnalysis.builder()
        .method(method)
        .methodReason(methodReason)
        .currentMarketCapYi(marketCap)
        .currentPrice(currentPrice)
        .totalSharesYi(totalSharesYi)
        .latestNetMargin(latestNm)
        .psMultiple(PS)
        .forecastRevenueY0(revY0)
        .forecastRevenueY1(revY1)
        .forecastRevenueY2(revY2)
        .fairCapY1Yi(fairCapY1)
        .fairCapY2Yi(fairCapY2)
        .verdict(verdict)
        .commentary(commentary)
        .buildPositionTip(buildTip)
        .build();
  }
}
