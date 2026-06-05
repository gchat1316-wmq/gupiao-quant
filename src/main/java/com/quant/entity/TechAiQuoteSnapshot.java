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
@Table(name = "tech_ai_quote_snapshot")
public class TechAiQuoteSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "quote_time", nullable = false)
    private LocalDateTime quoteTime;

    @Column(name = "latest_price", precision = 10, scale = 2)
    private BigDecimal latestPrice;

    @Column(name = "prev_close_price", precision = 10, scale = 2)
    private BigDecimal prevClosePrice;

    @Column(name = "open_price", precision = 10, scale = 2)
    private BigDecimal openPrice;

    @Column(name = "volume")
    private Long volume;

    @Column(name = "amount", precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(name = "turnover_rate", precision = 10, scale = 4)
    private BigDecimal turnoverRate;

    @Column(name = "minute1_open_price", precision = 10, scale = 2)
    private BigDecimal minute1OpenPrice;

    @Column(name = "minute1_time")
    private LocalDateTime minute1Time;

    @Column(name = "minute5_open_price", precision = 10, scale = 2)
    private BigDecimal minute5OpenPrice;

    @Column(name = "minute5_time")
    private LocalDateTime minute5Time;

    @Column(name = "source", length = 20)
    private String source = "qmt";

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
