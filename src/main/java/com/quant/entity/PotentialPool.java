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

/**
 * 潜力监控股票池。
 * <p>
 * 持仓/告警字段已迁至 {@link InvestPositionCommon}（pool_type = 'potential'）。
 * 服务层通过 {@link com.quant.repository.InvestPositionCommonRepository} 读写持仓数据。
 *
 * @see InvestPositionCommon
 */
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

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
