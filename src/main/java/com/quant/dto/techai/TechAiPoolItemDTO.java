package com.quant.dto.techai;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TechAiPoolItemDTO {
  private Integer id;
  private String stockCode;
  private String qmtCode;
  private String stockName;
  private String status;
  private String memo;
  private BigDecimal latestPrice;
  private BigDecimal dailyChangePct;
  private BigDecimal turnoverRate;
  private Long volume;
  private LocalDateTime quoteTime;
  private BigDecimal alertMinute1mPct;
  private BigDecimal alertMinute5mPct;
  private BigDecimal alertDailyPct;
  private BigDecimal alertThreeDayPct;
  private BigDecimal alertTurnoverRatioPct;

  // ===== 持仓聚合 =====
  private BigDecimal entryPrice;
  private BigDecimal positionLots;
  private BigDecimal avgCost;
  private BigDecimal totalInvested;
  private Integer addCount;
  private BigDecimal lastAddPrice;
  private BigDecimal peakPrice;
  private BigDecimal stopPrice;
  private BigDecimal realizedPnl;
  private String positionState;
  private Boolean takeProfitDone;
  private LocalDateTime openedAt;

  // ===== 策略参数 =====
  private BigDecimal addStepPct;
  private BigDecimal trailPct;
  private String addSizeSchedule;
  private BigDecimal maxLots;
  private BigDecimal takeProfitPct;
  private Boolean breakevenAfterTp;
  private Integer timeStopDays;
  private Boolean useAtr;
  private Integer atrPeriod;
  private BigDecimal atrAddMult;
  private BigDecimal atrTrailMult;
  private BigDecimal targetSellPrice;

  // ===== 实时计算（不落库） =====
  private BigDecimal nextAddPrice;
  private BigDecimal nextAddLots;
  private BigDecimal currentStopPrice;
  private BigDecimal floatingPnl;
  private BigDecimal floatingPnlPct;
  private BigDecimal atrValue;
  private Boolean stopBelowCost;

  /** 待办信号：ADD / STOP / TP / null */
  private String pendingSignal;

  /** 策略路线图：watching 状态下按现价+参数预演全部档位，holding 状态下为空列表。 */
  private java.util.List<StrategyLevelDTO> roadmap;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
