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
@Table(name = "trade_stock_financial")
public class TradeStockFinancial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "revenue", precision = 20, scale = 2)
    private BigDecimal revenue;

    @Column(name = "net_profit", precision = 20, scale = 2)
    private BigDecimal netProfit;

    @Column(name = "eps", precision = 10, scale = 4)
    private BigDecimal eps;

    @Column(name = "roe", precision = 10, scale = 4)
    private BigDecimal roe;

    @Column(name = "roa", precision = 10, scale = 4)
    private BigDecimal roa;

    @Column(name = "gross_margin", precision = 10, scale = 4)
    private BigDecimal grossMargin;

    @Column(name = "revenue_yoy", precision = 10, scale = 4)
    private BigDecimal revenueYoy;

    @Column(name = "deducted_net_profit_yoy", precision = 10, scale = 4)
    private BigDecimal deductedNetProfitYoy;

    @Column(name = "deducted_net_profit_ttm", precision = 20, scale = 2)
    private BigDecimal deductedNetProfitTtm;

    @Column(name = "net_margin", precision = 10, scale = 4)
    private BigDecimal netMargin;

    @Column(name = "debt_ratio", precision = 10, scale = 4)
    private BigDecimal debtRatio;

    @Column(name = "current_ratio", precision = 10, scale = 4)
    private BigDecimal currentRatio;

    @Column(name = "operating_cashflow", precision = 20, scale = 2)
    private BigDecimal operatingCashflow;

    @Column(name = "total_assets", precision = 20, scale = 2)
    private BigDecimal totalAssets;

    @Column(name = "total_equity", precision = 20, scale = 2)
    private BigDecimal totalEquity;

    @Column(name = "data_source", length = 20)
    private String dataSource;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
