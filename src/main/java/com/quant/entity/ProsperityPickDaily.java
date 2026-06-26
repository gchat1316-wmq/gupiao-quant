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

@Getter
@Setter
@Entity
@Table(name = "prosperity_pick_daily")
public class ProsperityPickDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "snap_date", nullable = false)
    private LocalDate snapDate;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 50)
    private String stockName;

    @Column(name = "sector_name", length = 64)
    private String sectorName;

    @Column(name = "finance_score", precision = 8, scale = 2)
    private BigDecimal financeScore;

    @Column(name = "mainline_score", precision = 8, scale = 2)
    private BigDecimal mainlineScore;

    @Column(name = "combined_score", precision = 8, scale = 2)
    private BigDecimal combinedScore;

    @Column(name = "net_margin_avg_4q", precision = 8, scale = 4)
    private BigDecimal netMarginAvg4q;

    @Column(name = "main_biz_ratio", precision = 8, scale = 4)
    private BigDecimal mainBizRatio;

    @Column(name = "latest_price", precision = 10, scale = 2)
    private BigDecimal latestPrice;

    @Column(name = "ai_report_json", columnDefinition = "LONGTEXT")
    private String aiReportJson;

    @Column(name = "price_low", precision = 10, scale = 2)
    private BigDecimal priceLow;

    @Column(name = "price_mid", precision = 10, scale = 2)
    private BigDecimal priceMid;

    @Column(name = "price_high", precision = 10, scale = 2)
    private BigDecimal priceHigh;

    @Column(name = "buy_left_price", precision = 10, scale = 2)
    private BigDecimal buyLeftPrice;

    @Column(name = "buy_right_price", precision = 10, scale = 2)
    private BigDecimal buyRightPrice;

    @Column(name = "sell_target_1", precision = 10, scale = 2)
    private BigDecimal sellTarget1;

    @Column(name = "sell_target_2", precision = 10, scale = 2)
    private BigDecimal sellTarget2;

    @Column(name = "stop_loss_price", precision = 10, scale = 2)
    private BigDecimal stopLossPrice;

    @Column(name = "core_position_pct", precision = 6, scale = 2)
    private BigDecimal corePositionPct;

    @Column(name = "tactical_position_pct", precision = 6, scale = 2)
    private BigDecimal tacticalPositionPct;

    @Column(name = "action_signal", length = 20)
    private String actionSignal;

    @Column(name = "degraded")
    private Integer degraded;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 板块归属备注: 一只股票同时入选多个板块时记录其它板块名 */
    @Column(name = "memo", columnDefinition = "TEXT")
    private String memo;

    /** 近3季度营收同比最小值(%) - 替代旧的近4季净利率 */
    @Column(name = "revenue_yoy_min_3q", precision = 10, scale = 4)
    private BigDecimal revenueYoyMin3q;
}
