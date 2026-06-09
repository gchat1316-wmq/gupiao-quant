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

    @Column(name = "pool_type", nullable = false, length = 10)
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

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
