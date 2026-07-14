package com.quant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "invest_xiebo_recent_watch")
public class InvestXieboRecentWatch {

    @Id
    @Column(name = "stock_code", nullable = false, length = 16)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 64)
    private String stockName;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "created_by_admin_id")
    private Long createdByAdminId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}