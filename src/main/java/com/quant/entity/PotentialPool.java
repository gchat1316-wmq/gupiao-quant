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
@Table(name = "potential_pool")
public class PotentialPool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "stock_code", nullable = false, length = 20, unique = true)
    private String stockCode;

    @Column(name = "stock_name", length = 255)
    private String stockName;

    @Column(name = "status", length = 10)
    private String status = "watching";

    @Column(name = "memo", columnDefinition = "TEXT")
    private String memo;

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

    // ===== 策略参数 =====
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
