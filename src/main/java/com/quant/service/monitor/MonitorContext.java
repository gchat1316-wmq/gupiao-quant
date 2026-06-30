package com.quant.service.monitor;

import com.quant.entity.InvestPositionCommon;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RuleEngine 输入上下文。包含一次扫描中能用到的全部事实：
 *  - 当前行情 (latest / openToday / prevClose)
 *  - 计算指标 (ATR / 1m-open / 5m-open / 换手率 / 3日前收盘)
 *  - 该位置的状态 (InvestPositionCommon)
 */
@Data
@Builder
public class MonitorContext {
    private InvestPositionCommon position;

    private String stockCode;
    private String stockName;

    private BigDecimal latest;
    private BigDecimal openToday;
    private BigDecimal prevClose;
    private BigDecimal minute1Open;
    private BigDecimal minute5Open;
    private BigDecimal turnoverRate;
    private BigDecimal avgTurnoverRate5d;
    private BigDecimal closePrice3DaysAgo;

    /** ATR(period) 计算结果 — 缺失时相关 ATR 规则静默跳过 */
    private BigDecimal atr;

    private LocalDateTime quoteTime;
}
