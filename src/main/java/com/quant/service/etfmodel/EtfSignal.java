package com.quant.service.etfmodel;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/** 一次省心 ETF 规则命中事件，由 {@link EtfSignalEngine} 产出。 */
@Data
@Builder
public class EtfSignal {

  public static final String TP1 = "etf_tp1";
  public static final String TP2 = "etf_tp2";
  public static final String TRAIL_EXIT = "etf_trail_exit";
  public static final String SL1 = "etf_sl1";
  public static final String SL2 = "etf_sl2";
  public static final String PORTFOLIO_GUARD = "etf_portfolio_guard";
  public static final String RECOUP_READY = "etf_recoup_ready";

  private String stockCode;
  private String stockName;
  private String signalType;
  private String title;
  private String content;
  private BigDecimal triggerPrice;
  private LocalDateTime triggeredAt;

  /** 买入/加仓类建议（冷静期内推送需附加冷静提醒） */
  private boolean buyAdvice;
}
