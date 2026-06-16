package com.quant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "invest_stock_pool")
public class InvestStockPool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "stock_code", nullable = false, length = 20, unique = true)
    private String stockCode;

    @Column(name = "stock_name", length = 255)
    private String stockName;

    @Column(name = "pool_type", nullable = false, length = 20)
    private String poolType;

    @Column(name = "undervalued_price", precision = 10, scale = 2)
    private BigDecimal undervaluedPrice;

    @Column(name = "fair_price", precision = 10, scale = 2)
    private BigDecimal fairPrice;

    @Column(name = "overvalued_price", precision = 10, scale = 2)
    private BigDecimal overvaluedPrice;

    @Column(name = "target_buy_price", precision = 10, scale = 2)
    private BigDecimal targetBuyPrice;

    @Column(name = "target_sell_price", precision = 10, scale = 2)
    private BigDecimal targetSellPrice;

    @Column(name = "revenue_forecast_y0", precision = 10, scale = 2)
    private BigDecimal revenueForecastY0;

    @Column(name = "revenue_forecast_y1", precision = 10, scale = 2)
    private BigDecimal revenueForecastY1;

    @Column(name = "revenue_forecast_y2", precision = 10, scale = 2)
    private BigDecimal revenueForecastY2;

    @Column(name = "rev_2023", precision = 10, scale = 2)
    private BigDecimal revenue2023;

    @Column(name = "rev_2024", precision = 10, scale = 2)
    private BigDecimal revenue2024;

    @Column(name = "rev_2025", precision = 10, scale = 2)
    private BigDecimal revenue2025;

    @Column(name = "q1_gross_margin", precision = 8, scale = 2)
    private BigDecimal q1GrossMargin;

    @Column(name = "q1_net_margin", precision = 8, scale = 2)
    private BigDecimal q1NetMargin;

    @Column(name = "q1_revenue_growth", precision = 8, scale = 2)
    private BigDecimal q1RevenueGrowth;

    @Column(name = "min_ps_5y", precision = 10, scale = 2)
    private BigDecimal minPs5y;

    @Column(name = "target_market_cap", precision = 12, scale = 2)
    private BigDecimal targetMarketCap;

    @Column(name = "current_market_cap", precision = 12, scale = 2)
    private BigDecimal currentMarketCap;

    @Column(name = "ytd_gain_pct", precision = 8, scale = 2)
    private BigDecimal ytdGainPct;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "pool_data_updated_at")
    private LocalDateTime poolDataUpdatedAt;

    @Column(name = "pool_update_error", length = 1000)
    private String poolUpdateError;

    @Column(name = "profit_level", length = 20)
    private String profitLevel;

    @Column(name = "valuation_range", length = 20)
    private String valuationRange;

    @Column(name = "memo", columnDefinition = "TEXT")
    private String memo;

    @Column(name = "target_price", precision = 10, scale = 2)
    private BigDecimal targetPrice;

    @Column(name = "status", length = 10)
    private String status = "watching";

    @Column(name = "alert_state", length = 20)
    private String alertState = "none";

    @Column(name = "last_alert_at")
    private LocalDateTime lastAlertAt;

    @Column(name = "alert_minute_1m_pct", precision = 8, scale = 2)
    private BigDecimal alertMinute1mPct;

    @Column(name = "alert_minute_5m_pct", precision = 8, scale = 2)
    private BigDecimal alertMinute5mPct;

    @Column(name = "alert_daily_pct", precision = 8, scale = 2)
    private BigDecimal alertDailyPct;

    @Column(name = "alert_three_day_pct", precision = 8, scale = 2)
    private BigDecimal alertThreeDayPct;

    @Column(name = "alert_turnover_ratio_pct", precision = 8, scale = 2)
    private BigDecimal alertTurnoverRatioPct;

    // ===== 持仓策略：成交聚合（由流水重算） =====
    @Column(name = "entry_price", precision = 10, scale = 2)
    private BigDecimal entryPrice;

    @Column(name = "position_lots", precision = 10, scale = 2)
    private BigDecimal positionLots = BigDecimal.ZERO;

    @Column(name = "avg_cost", precision = 10, scale = 2)
    private BigDecimal avgCost;

    @Column(name = "total_invested", precision = 14, scale = 2)
    private BigDecimal totalInvested;

    @Column(name = "add_count")
    private Integer addCount = 0;

    @Column(name = "last_add_price", precision = 10, scale = 2)
    private BigDecimal lastAddPrice;

    @Column(name = "peak_price", precision = 10, scale = 2)
    private BigDecimal peakPrice;

    @Column(name = "stop_price", precision = 10, scale = 2)
    private BigDecimal stopPrice;

    @Column(name = "realized_pnl", precision = 14, scale = 2)
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    @Column(name = "position_state", length = 20)
    private String positionState = "none";

    @Column(name = "take_profit_done")
    private Integer takeProfitDone = 0;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    // ===== 持仓策略：参数 =====
    @Column(name = "add_step_pct", precision = 6, scale = 2)
    private BigDecimal addStepPct;

    @Column(name = "trail_pct", precision = 6, scale = 2)
    private BigDecimal trailPct;

    @Column(name = "add_size_schedule", length = 50)
    private String addSizeSchedule;

    @Column(name = "max_lots", precision = 10, scale = 2)
    private BigDecimal maxLots;

    @Column(name = "take_profit_pct", precision = 6, scale = 2)
    private BigDecimal takeProfitPct;

    @Column(name = "breakeven_after_tp")
    private Integer breakevenAfterTp = 1;

    @Column(name = "time_stop_days")
    private Integer timeStopDays;

    @Column(name = "use_atr")
    private Integer useAtr = 0;

    @Column(name = "atr_period")
    private Integer atrPeriod;

    @Column(name = "atr_add_mult", precision = 6, scale = 2)
    private BigDecimal atrAddMult;

    @Column(name = "atr_trail_mult", precision = 6, scale = 2)
    private BigDecimal atrTrailMult;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
