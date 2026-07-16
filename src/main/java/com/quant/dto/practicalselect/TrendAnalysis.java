package com.quant.dto.practicalselect;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 走势分析（"完美走势"）。
 *
 * <p>数据源：trade_stock_daily（日 K），本地聚合为月 K。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendAnalysis {

  /** 一句话走势总结（供前端卡片展示） */
  private String summary;

  /** 月度 K 线数据（按月份升序） */
  private List<MonthlyBar> monthlyBars;

  /** 本月至今涨幅 % */
  private Double monthToDateReturnPct;

  /** 最近一个完整月涨幅 % */
  private Double lastMonthReturnPct;

  /** 最近 60 日最大涨幅 %（平台突破判定） */
  private Double sixtyDayMaxGainPct;

  /** 最近 60 日最大回撤 % */
  private Double sixtyDayMaxDrawdownPct;

  /** 是否突破近 N 月平台（最近 1-2 月收盘价 > 之前 6 月最高收盘价） */
  private boolean breakoutDetected;

  /** 突破说明文本 */
  private String breakoutNote;

  /** 最近大阳线（涨幅 ≥ 9.5% 的交易日） */
  private List<BigYangLine> recentBigYang;

  /** 数据覆盖天数 */
  private int dataDays;

  /** 数据起始日期 */
  private String dataStartDate;

  /** 数据截止日期 */
  private String dataEndDate;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class MonthlyBar {
    /** 月份标签，如 "2025-09" */
    private String month;

    /** 月末收盘价 */
    private Double close;

    /** 月内最高 */
    private Double high;

    /** 月内最低 */
    private Double low;

    /** 月内成交量（手） */
    private Long volume;

    /** 月涨幅 %（月末对月初） */
    private Double returnPct;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class BigYangLine {
    private String date;
    private Double openPrice;
    private Double closePrice;
    private Double highPrice;
    private Double pctChange;
    private Double turnoverRate;
  }
}
