package com.quant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "trade_stock_info")
public class TradeStockInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "stock_code", nullable = false, length = 20, unique = true)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 64)
    private String stockName;

    @Column(name = "exchange", length = 16)
    private String exchange;

    @Column(name = "industry", length = 64)
    private String industry;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
