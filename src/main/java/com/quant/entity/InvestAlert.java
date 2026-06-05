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
@Table(name = "invest_alert")
public class InvestAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "signal_type", nullable = false, length = 30)
    private String signalType;

    @Column(name = "level")
    private Integer level;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "trigger_price", precision = 10, scale = 2)
    private BigDecimal triggerPrice;

    @Column(name = "trigger_at")
    private LocalDateTime triggerAt;

    @Column(name = "channels", length = 100)
    private String channels;

    @Column(name = "pushed")
    private Integer pushed;

    @Column(name = "read_flag")
    private Integer readFlag;

    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
