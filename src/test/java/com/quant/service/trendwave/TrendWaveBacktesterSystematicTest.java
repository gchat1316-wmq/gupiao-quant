package com.quant.service.trendwave;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.quant.config.TrendWaveProperties;
import com.quant.entity.TradeStockDaily;
import com.quant.service.trendwave.TrendWaveBacktester.BacktestResult;
import com.quant.service.trendwave.TrendWaveBacktester.TradeRecord;

/**
 * 系统化收益回测：确定性合成日线，保证触发「筛选→涨停平台→回踩买点→持仓→止盈/止损」。
 */
class TrendWaveBacktesterSystematicTest {

  @Test
  void systematicBasketBacktestProducesMetricsAndReport() throws Exception {
    TrendWaveProperties props = new TrendWaveProperties();
    props.getScreening().setVolumeExpandRatio(BigDecimal.valueOf(1.05));
    props.getPullback().setShrinkVolumeRatio(BigDecimal.valueOf(0.80));
    TrendWaveBacktester bt = new TrendWaveBacktester(props);

    List<BacktestResult> perStock = new ArrayList<>();
    String[] codes = {"600584.SH", "601138.SH", "002371.SZ", "603019.SH", "600745.SH"};
    String[] names = {"长电科技", "工业富联", "北方华创", "中科曙光", "闻泰科技"};
    for (int i = 0; i < codes.length; i++) {
      List<TradeStockDaily> daily = synthPullbackCycle(codes[i], 3 + i);
      BacktestResult r = bt.run(codes[i], names[i], daily);
      perStock.add(r);
      System.out.println(r.getSummary());
      for (TradeRecord t : r.getTradeRecords()) {
        System.out.printf(
            "  %s %s -> %s %s%% (%s)%n",
            t.getBuyType(), t.getEntryDate(), t.getExitDate(), t.getPnlPct(), t.getExitReason());
      }
    }

    BacktestResult basket = bt.runBasket(perStock);
    System.out.println(basket.getSummary());

    Path report = Path.of("/opt/cursor/artifacts/trend-wave-backtest-report.md");
    Files.writeString(report, renderReport(perStock, basket), StandardCharsets.UTF_8);
    System.out.println("Report: " + report);

    assertThat(basket.getTrades()).as("组合应产生交易").isGreaterThan(0);
    assertThat(basket.getWinRate()).isNotNull();
    assertThat(Files.exists(report)).isTrue();
    assertThat(
            perStock.stream()
                .flatMap(r -> r.getTradeRecords().stream())
                .anyMatch(t -> t.getExitReason() != null))
        .isTrue();
  }

  /**
   * 主板 10% 涨停可识别。结构：缓涨堆多头 → 放量涨停 → 缩量回踩站上5日 → 主升 → 回撤止盈，循环多次。
   */
  static List<TradeStockDaily> synthPullbackCycle(String code, int cycles) {
    List<TradeStockDaily> out = new ArrayList<>();
    LocalDate d = LocalDate.of(2024, 1, 2);
    BigDecimal price = new BigDecimal("20.00");

    // 80 日缓涨：建立 MA60 多头 + 抬升量能
    for (int i = 0; i < 80; i++) {
      d = nextTradeDate(d);
      BigDecimal open = price;
      BigDecimal close = price.multiply(BigDecimal.valueOf(1.008)).setScale(2, RoundingMode.HALF_UP);
      long vol = 1_500_000L + i * 15_000L;
      out.add(bar(code, d, open, close, vol));
      price = close;
    }

    for (int c = 0; c < cycles; c++) {
      // 涨停（主板 10%）：最低价=开盘价，形成平台 [open, open]
      d = nextTradeDate(d);
      BigDecimal openLu = price;
      BigDecimal closeLu =
          openLu.multiply(BigDecimal.valueOf(1.10)).setScale(2, RoundingMode.HALF_UP);
      TradeStockDaily lu = bar(code, d, openLu, closeLu, 8_000_000L);
      lu.setLowPrice(openLu);
      lu.setHighPrice(closeLu);
      out.add(lu);
      price = closeLu;

      // 缩量回踩：先跌回平台，最后一天「盘中触及平台 + 收盘站上5日线」
      for (int j = 0; j < 2; j++) {
        d = nextTradeDate(d);
        BigDecimal open = price;
        BigDecimal close = open.multiply(BigDecimal.valueOf(0.96)).setScale(2, RoundingMode.HALF_UP);
        out.add(bar(code, d, open, close, 1_000_000L));
        price = close;
      }
      // 买点日：低点触及涨停开盘价，收盘强势收阳（高于开盘与近5日均线）
      d = nextTradeDate(d);
      BigDecimal buyOpen = openLu.multiply(BigDecimal.valueOf(0.995)).setScale(2, RoundingMode.HALF_UP);
      BigDecimal buyClose = price.multiply(BigDecimal.valueOf(1.06)).setScale(2, RoundingMode.HALF_UP);
      // 确保收盘明显高于平台与开盘
      if (buyClose.compareTo(openLu.multiply(BigDecimal.valueOf(1.04))) < 0) {
        buyClose = openLu.multiply(BigDecimal.valueOf(1.06)).setScale(2, RoundingMode.HALF_UP);
      }
      TradeStockDaily buyBar = bar(code, d, buyOpen, buyClose, 1_500_000L);
      buyBar.setLowPrice(openLu); // 盘中触及平台
      buyBar.setHighPrice(buyClose.multiply(BigDecimal.valueOf(1.01)).setScale(2, RoundingMode.HALF_UP));
      out.add(buyBar);
      price = buyClose;

      // 主升 8 日（制造 >15% 盈利）
      for (int j = 0; j < 8; j++) {
        d = nextTradeDate(d);
        BigDecimal open = price;
        BigDecimal close = open.multiply(BigDecimal.valueOf(1.035)).setScale(2, RoundingMode.HALF_UP);
        out.add(bar(code, d, open, close, 4_000_000L));
        price = close;
      }

      // 回撤触发 T1/T2 移动止盈
      for (int j = 0; j < 4; j++) {
        d = nextTradeDate(d);
        BigDecimal open = price;
        BigDecimal close = open.multiply(BigDecimal.valueOf(0.96)).setScale(2, RoundingMode.HALF_UP);
        out.add(bar(code, d, open, close, 5_000_000L));
        price = close;
      }

      // 再缓涨恢复多头，准备下一轮
      for (int j = 0; j < 15; j++) {
        d = nextTradeDate(d);
        BigDecimal open = price;
        BigDecimal close = open.multiply(BigDecimal.valueOf(1.01)).setScale(2, RoundingMode.HALF_UP);
        out.add(bar(code, d, open, close, 2_500_000L));
        price = close;
      }
    }
    return out;
  }

  private static LocalDate nextTradeDate(LocalDate d) {
    LocalDate n = d.plusDays(1);
    while (n.getDayOfWeek().getValue() >= 6) {
      n = n.plusDays(1);
    }
    return n;
  }

  private static TradeStockDaily bar(
      String code, LocalDate d, BigDecimal open, BigDecimal close, long vol) {
    TradeStockDaily b = new TradeStockDaily();
    b.setStockCode(code);
    b.setTradeDate(d);
    b.setOpenPrice(open.setScale(2, RoundingMode.HALF_UP));
    b.setClosePrice(close.setScale(2, RoundingMode.HALF_UP));
    BigDecimal high = open.max(close).multiply(BigDecimal.valueOf(1.01)).setScale(2, RoundingMode.HALF_UP);
    BigDecimal low = open.min(close).multiply(BigDecimal.valueOf(0.99)).setScale(2, RoundingMode.HALF_UP);
    b.setHighPrice(high);
    b.setLowPrice(low);
    b.setVolume(vol);
    return b;
  }

  private String renderReport(List<BacktestResult> perStock, BacktestResult basket) {
    StringBuilder sb = new StringBuilder();
    sb.append("# 趋势波段系统化回测报告\n\n");
    sb.append("> 确定性合成主板科技股日线，验证：筛选 → 涨停平台 → 回踩买点 → 持仓风控 → 止盈/止损 → 统计。\n\n");
    sb.append("## 组合汇总\n\n");
    sb.append("| 指标 | 值 |\n|---|---|\n");
    sb.append("| 交易笔数 | ").append(basket.getTrades()).append(" |\n");
    sb.append("| 胜率 | ").append(basket.getWinRate()).append("% |\n");
    sb.append("| 平均盈利% | ").append(basket.getAvgWinPct()).append(" |\n");
    sb.append("| 平均亏损% | ").append(basket.getAvgLossPct()).append(" |\n");
    sb.append("| 盈亏比 | ").append(basket.getProfitFactor()).append(" |\n");
    sb.append("| 期望收益% | ").append(basket.getExpectancyPct()).append(" |\n");
    sb.append("| 复利总收益% | ").append(basket.getTotalReturnPct()).append(" |\n");
    sb.append("| 最大回撤% | ").append(basket.getMaxDrawdownPct()).append(" |\n\n");

    sb.append("## 分标的\n\n");
    sb.append("| 代码 | 交易 | 胜 | 负 | 胜率 | 盈亏比 | 期望% | 收益% | 回撤% |\n");
    sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
    for (BacktestResult r : perStock) {
      sb.append("| ")
          .append(r.getStockCode())
          .append(" | ")
          .append(r.getTrades())
          .append(" | ")
          .append(r.getWins())
          .append(" | ")
          .append(r.getLosses())
          .append(" | ")
          .append(r.getWinRate())
          .append(" | ")
          .append(r.getProfitFactor())
          .append(" | ")
          .append(r.getExpectancyPct())
          .append(" | ")
          .append(r.getTotalReturnPct())
          .append(" | ")
          .append(r.getMaxDrawdownPct())
          .append(" |\n");
    }

    sb.append("\n## 成交明细\n\n");
    sb.append("| 代码 | 买点 | 入场 | 出场 | 入价 | 出价 | 盈亏% | 原因 |\n");
    sb.append("|---|---|---|---|---:|---:|---:|---|\n");
    for (BacktestResult r : perStock) {
      for (TradeRecord t : r.getTradeRecords()) {
        sb.append("| ")
            .append(t.getStockCode())
            .append(" | ")
            .append(t.getBuyType())
            .append(" | ")
            .append(t.getEntryDate())
            .append(" | ")
            .append(t.getExitDate())
            .append(" | ")
            .append(t.getEntryPrice())
            .append(" | ")
            .append(t.getExitPrice())
            .append(" | ")
            .append(t.getPnlPct())
            .append(" | ")
            .append(t.getExitReason())
            .append(" |\n");
      }
    }
    sb.append("\n## 链路结论\n\n");
    sb.append("1. **规则链路已接通**：日线推进可完成筛选→setup→买入→退出并落统计。\n");
    sb.append("2. **实盘链路**：`money_stock_pool` 入池 → `TrendWaveScanScheduler` → `TrendWaveRuleEngine` → `money_event` + Server酱 → 页面确认 → `money_trade_leg`。\n");
    sb.append("3. **本报告为合成数据验证**；真实历史回测需连库拉取 `trade_stock_daily`（当前云环境无生产库凭证）。\n");
    return sb.toString();
  }
}
