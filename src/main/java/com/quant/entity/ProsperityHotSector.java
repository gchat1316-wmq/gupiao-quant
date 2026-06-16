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
@Table(name = "prosperity_hot_sector")
public class ProsperityHotSector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "snap_date", nullable = false)
    private LocalDate snapDate;

    @Column(name = "sector_code", length = 32)
    private String sectorCode;

    @Column(name = "sector_name", nullable = false, length = 64)
    private String sectorName;

    @Column(name = "rank_no", nullable = false)
    private Integer rankNo;

    @Column(name = "change_1d", precision = 8, scale = 4)
    private BigDecimal change1d;

    @Column(name = "change_5d", precision = 8, scale = 4)
    private BigDecimal change5d;

    @Column(name = "change_20d", precision = 8, scale = 4)
    private BigDecimal change20d;

    @Column(name = "capital_inflow_5d", precision = 20, scale = 2)
    private BigDecimal capitalInflow5d;

    @Column(name = "up_count")
    private Integer upCount;

    @Column(name = "down_count")
    private Integer downCount;

    @Column(name = "lead_stock", length = 64)
    private String leadStock;

    @Column(name = "lead_stock_change", precision = 8, scale = 4)
    private BigDecimal leadStockChange;

    @Column(name = "persistence_days")
    private Integer persistenceDays;

    @Column(name = "score", precision = 8, scale = 2)
    private BigDecimal score;

    @Column(name = "ai_narrative", columnDefinition = "TEXT")
    private String aiNarrative;

    @Column(name = "data_source", length = 20)
    private String dataSource;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
