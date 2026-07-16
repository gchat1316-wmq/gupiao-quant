package com.quant.dto.monitor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.quant.entity.InvestPositionCommon;

import lombok.Data;

/**
 * 统一监控池表格的单条响应。包含行情快照 + 全部触发参数 + 现有 pct 阈值。 由 MonitorService.scan() 聚合各种数据源 (QuoteSnapshot,
 * TradeStockBasic) 之后返回。
 */
@Data
public class MonitorPoolItemDTO {
  private String stockCode;
  private String stockName;
  private String poolType;
  private String monitorMode;
  private String serverchanTemplate;

  private BigDecimal latestPrice;
  private BigDecimal dailyChangePct;
  private LocalDateTime quoteTime;

  // 新增触发参数 (固定价 / ATR / 止盈止损)
  private BigDecimal fixedBuyPrice;
  private BigDecimal fixedSellPrice;
  private Integer fixedBuyEnabled;
  private Integer fixedSellEnabled;
  private BigDecimal atrAlertAmplitude;
  private Integer atrAlertEnabled;
  private BigDecimal stopLossPct;
  private BigDecimal takeProfitPct;
  private BigDecimal entryPrice;
  private BigDecimal positionLots;

  // 既有 pct 阈值
  private BigDecimal alertMinute1mPct;
  private BigDecimal alertMinute5mPct;
  private BigDecimal alertDailyPct;
  private BigDecimal alertThreeDayPct;
  private BigDecimal alertTurnoverRatioPct;

  private String status;
  private LocalDateTime lastAlertAt;

  /** 从 InvestPositionCommon 投影，前置补 stockName / latestPrice / dailyChangePct。 */
  public static MonitorPoolItemDTO from(
      InvestPositionCommon p, String stockName, BigDecimal latest, BigDecimal dailyChange) {
    MonitorPoolItemDTO dto = new MonitorPoolItemDTO();
    dto.stockCode = p.getStockCode();
    dto.stockName = stockName;
    dto.poolType = p.getPoolType();
    dto.monitorMode = p.getMonitorMode();
    dto.serverchanTemplate = p.getServerchanTemplate();
    dto.latestPrice = latest;
    dto.dailyChangePct = dailyChange;
    dto.fixedBuyPrice = p.getFixedBuyPrice();
    dto.fixedSellPrice = p.getFixedSellPrice();
    dto.fixedBuyEnabled = p.getFixedBuyEnabled();
    dto.fixedSellEnabled = p.getFixedSellEnabled();
    dto.atrAlertAmplitude = p.getAtrAlertAmplitude();
    dto.atrAlertEnabled = p.getAtrAlertEnabled();
    dto.stopLossPct = p.getStopLossPct();
    dto.takeProfitPct = p.getTakeProfitPct();
    dto.entryPrice = p.getEntryPrice();
    dto.positionLots = p.getPositionLots();
    dto.alertMinute1mPct = p.getAlertMinute1mPct();
    dto.alertMinute5mPct = p.getAlertMinute5mPct();
    dto.alertDailyPct = p.getAlertDailyPct();
    dto.alertThreeDayPct = p.getAlertThreeDayPct();
    dto.alertTurnoverRatioPct = p.getAlertTurnoverRatioPct();
    dto.status = p.getStatus();
    dto.lastAlertAt = p.getLastAlertAt();
    return dto;
  }
}
