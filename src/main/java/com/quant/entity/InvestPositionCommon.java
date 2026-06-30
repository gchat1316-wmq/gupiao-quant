package com.quant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 三池持仓状态聚合表（invest_stock_pool / tech_ai_pool / potential_pool 共用）。
 * 统一存储：告警阈值 + 持仓策略聚合 + 持仓策略参数。
 * <p>
 * 主键为 (pool_type, stock_code) 组合，确保同一股票可出现在不同池中。
 * 持仓流水（成交记录）暂存于各池独立的 *_position_fill 表，待后续统一。
 *
 * @see InvestStockPool
 * @see TechAiPool
 * @see PotentialPool
 */
@Getter
@Setter
@Entity
@Table(name = "invest_position_common")
public class InvestPositionCommon {

    // ===== 主键 =====
    @Id
    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "pool_type", nullable = false, length = 20)
    private String poolType;

    // ===== 状态 =====
    @Column(name = "status", length = 10)
    private String status = "watching";

    @Column(name = "alert_state", length = 20)
    private String alertState = "none";

    @Column(name = "last_alert_at")
    private LocalDateTime lastAlertAt;

    // ===== 告警阈值 =====
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

    // ===== 持仓聚合（由流水重算） =====
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

    // ===== 持仓策略参数 =====
    @Column(name = "target_sell_price", precision = 10, scale = 2)
    private BigDecimal targetSellPrice;

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
