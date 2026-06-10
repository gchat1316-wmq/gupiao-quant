package com.quant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "invest_market_recap")
public class InvestMarketRecap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String market;

    @Column(name = "recap_date", nullable = false)
    private LocalDate recapDate;

    @Column(name = "recap_type", nullable = false, length = 16)
    private String recapType;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "indexes_summary", length = 512)
    private String indexesSummary;

    @Column(name = "advance_decline", length = 64)
    private String advanceDecline;

    @Column(name = "limit_up")
    private Integer limitUp;

    @Column(name = "limit_down")
    private Integer limitDown;

    @Column(length = 64)
    private String sentiment;

    @Column(columnDefinition = "TEXT")
    private String sectors;

    @Column(columnDefinition = "TEXT")
    private String risks;

    @Column(name = "key_data", columnDefinition = "TEXT")
    private String keyData;

    @Column(columnDefinition = "TEXT")
    private String catalysts;

    @Column(name = "next_day_strategy", columnDefinition = "TEXT")
    private String nextDayStrategy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
