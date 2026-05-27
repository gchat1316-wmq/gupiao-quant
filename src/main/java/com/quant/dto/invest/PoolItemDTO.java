package com.quant.dto.invest;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class PoolItemDTO {
    private Integer id;
    private String stockCode;
    private String stockName;
    private String poolType;
    private String poolTypeLabel;
    private String memo;

    /** 估值价格 */
    private BigDecimal undervaluedPrice;
    private BigDecimal fairPrice;
    private BigDecimal overvaluedPrice;

    /** 操作意向价格 */
    private BigDecimal targetBuyPrice;
    private BigDecimal targetSellPrice;
    /** @deprecated 旧字段，已被 targetBuyPrice / targetSellPrice 取代，保留供兼容 */
    @Deprecated
    private BigDecimal targetPrice;

    /** 营收预测（亿元） */
    private BigDecimal revenueForecastY0;
    private BigDecimal revenueForecastY1;
    private BigDecimal revenueForecastY2;

    /** 持仓状态 */
    private String status;
    private String statusLabel;

    /** 提醒状态：none / buy_alerted / sell_alerted */
    private String alertState;
    private LocalDateTime lastAlertAt;

    /** 派生字段：最新收盘价 */
    private BigDecimal latestPrice;
    /** 派生字段：年初涨幅（%） */
    private BigDecimal ytdGain;
    /** 派生字段：当前市值（亿元） */
    private BigDecimal marketCap;

    /** 最新景气数据（保留兼容） */
    private BigDecimal latestRevenueYoy;
    private BigDecimal latestProfitYoy;
    private String latestLevel;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
