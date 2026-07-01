package com.quant.dto.invest;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PoolSaveRequest {
    private String keyword;
    private String poolType;
    private String memo;
    /** @deprecated 兼容旧字段 */
    @Deprecated
    private BigDecimal targetPrice;
    private String status;

    private BigDecimal undervaluedPrice;
    private BigDecimal fairPrice;
    private BigDecimal overvaluedPrice;
    private BigDecimal targetBuyPrice;
    private BigDecimal targetSellPrice;
    /** 2026-07-01 弹窗"消息监控" checkbox：勾选后把 target_buy_price 同步到 fixed_buy_price，价格触达发 server 酱 */
    private Boolean alertBuyEnabled;
    /** 2026-07-01 弹窗"消息监控" checkbox：勾选后把 target_sell_price 同步到 fixed_sell_price */
    private Boolean alertSellEnabled;
    private BigDecimal revenueForecastY0;
    private BigDecimal revenueForecastY1;
    private BigDecimal revenueForecastY2;

    private BigDecimal revenue2023;
    private BigDecimal revenue2024;
    private BigDecimal revenue2025;
    private BigDecimal q1GrossMargin;
    private BigDecimal q1NetMargin;
    private BigDecimal q1RevenueGrowth;
    private BigDecimal minPs5y;
    private BigDecimal targetMarketCap;
    private BigDecimal currentMarketCap;
    private BigDecimal ytdGainPct;
    private Integer displayOrder;
    private String profitLevel;
    private String valuationRange;
}
