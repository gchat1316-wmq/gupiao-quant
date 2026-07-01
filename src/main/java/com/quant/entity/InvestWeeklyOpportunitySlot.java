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
 * 每周机会点（3×3 卡片）槽位。
 *
 * 每个 pool_type 固定 9 个 slot（slotIndex 0~8）。空槽 stockCode=NULL。
 * 估值水平 (level) 不入库，读取时按 stockCode 实时联动 invest_stock_pool 算出。
 */
@Getter
@Setter
@Entity
@Table(name = "invest_weekly_opportunity_slot",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uk_pool_slot", columnNames = {"pool_type", "slot_index"}))
public class InvestWeeklyOpportunitySlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_type", nullable = false, length = 20)
    private String poolType;

    @Column(name = "slot_index", nullable = false)
    private Integer slotIndex;

    @Column(name = "stock_code", length = 16)
    private String stockCode;

    /**
     * 用户在 9 格编辑页手工填的股票名（与 stockPool 联动的 stockName 独立）。
     * 用途：stockCode 不在池中时，buildSlots 联动不到名称，此时用 userStockName 兜底显示。
     */
    @Column(name = "user_stock_name", length = 100)
    private String userStockName;

    @Column(name = "reason", length = 500)
    private String reason;

    /** 名称/候选股截图（佐证用），由 admin 在 9 格编辑页面上传；与 stockCode 无强绑定。 */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
