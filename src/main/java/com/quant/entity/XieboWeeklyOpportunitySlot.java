package com.quant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 谢博投资 · 每周重点股票 3×3 卡片槽位。
 *
 * poolType 限定为 watch / focus / explore 三个分类。
 * slotIndex 0~8；空槽 stockCode=NULL。
 */
@Getter
@Setter
@Entity
@Table(name = "xiebo_weekly_opportunity_slot",
        uniqueConstraints = @UniqueConstraint(name = "uk_xiebo_pool_slot", columnNames = {"pool_type", "slot_index"}))
public class XieboWeeklyOpportunitySlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_type", nullable = false, length = 20)
    private String poolType;

    @Column(name = "slot_index", nullable = false)
    private Integer slotIndex;

    @Column(name = "stock_code", length = 16)
    private String stockCode;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
