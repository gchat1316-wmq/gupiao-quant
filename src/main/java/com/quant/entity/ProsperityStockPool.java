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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 热点股票池 — 龙头候选"入池"动作的落地表。
 *
 * <p>与 {@code invest_stock_pool}(龙江投资股票池) 独立：前者面向热点选股流水线
 * 的短线/波段跟踪，后者面向中长期持仓。同一只股票在两个池子里的状态互不影响。
 *
 * <p>同一只股票只入一次，重复"入池"等价于刷新快照：累加 {@link #poolCount}、
 * 更新 {@link #lastAddedAt} / {@link #lastSnapDate}、在 {@link #memo} 末尾追加新推荐理由。
 */
@Getter
@Setter
@Entity
@Table(name = "prosperity_stock_pool")
public class ProsperityStockPool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "stock_code", nullable = false, length = 20, unique = true)
    private String stockCode;

    @Column(name = "stock_name", length = 50)
    private String stockName;

    /** watching/hit_target/stopped/expired —— 留给后续手工编辑 */
    @Column(name = "status", length = 20)
    private String status = "watching";

    /** 累计入池次数(同一股票多次入池累加) */
    @Column(name = "pool_count")
    private Integer poolCount = 1;

    /** 首次入池时间(永不更新) */
    @Column(name = "first_added_at", insertable = false, updatable = false)
    private LocalDateTime firstAddedAt;

    /** 最近一次入池时间(每次 promote 更新) */
    @Column(name = "last_added_at")
    private LocalDateTime lastAddedAt;

    /** 最近一次入池对应的流水线快照日期 */
    @Column(name = "last_snap_date")
    private LocalDate lastSnapDate;

    /** 上次入池时的板块 */
    @Column(name = "sector_name", length = 64)
    private String sectorName;

    /** 上次入池时的综合分(扁平存,避免联表) */
    @Column(name = "combined_score", precision = 8, scale = 2)
    private BigDecimal combinedScore;

    /** 上次入池时的现价 */
    @Column(name = "latest_price", precision = 10, scale = 2)
    private BigDecimal latestPrice;

    /** 上次入池时的建仓价 */
    @Column(name = "buy_left_price", precision = 10, scale = 2)
    private BigDecimal buyLeftPrice;

    /** 上次入池时的目标价 */
    @Column(name = "sell_target_1", precision = 10, scale = 2)
    private BigDecimal sellTarget1;

    /** 上次入池时的止损价 */
    @Column(name = "stop_loss_price", precision = 10, scale = 2)
    private BigDecimal stopLossPrice;

    /** 上次入池时的核心仓位上限% */
    @Column(name = "core_position_pct", precision = 6, scale = 2)
    private BigDecimal corePositionPct;

    /** 上次入池时的战术仓位% */
    @Column(name = "tactical_position_pct", precision = 6, scale = 2)
    private BigDecimal tacticalPositionPct;

    /** 上次入池时的操作信号 add/hold/reduce/observe */
    @Column(name = "action_signal", length = 20)
    private String actionSignal;

    /** 入池理由(每次入池追加一条) */
    @Column(name = "memo", columnDefinition = "TEXT")
    private String memo;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
