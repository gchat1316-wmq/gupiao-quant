package com.quant.dto.invest;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 每周机会点单格（一张卡片）返回结构。
 *
 * 字段说明：
 * - poolType / slotIndex：定位（slotIndex 0~8）
 * - stockCode / stockName：股票标识（空格时为 null）
 * - reason：推荐理由（前端最大 2 行展示）
 * - updatedAt：上次更新时间（admin 编辑该格时刷新）
 *
 * 注意：估值水平 (level) 不由后端计算，原因是 InvestStockPool 表无 currentPrice。
 * 前端应调 GET /api/invest/pool 拿到股票池快照后，用现有 inferValuationRange(item) 算出 level。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class WeeklyOpportunitySlotDTO {

    private String poolType;
    private Integer slotIndex;
    private String stockCode;
    /** 股票名（自动从 invest_stock_pool 联动；用户填的 userStockName 不影响此字段）。 */
    private String stockName;
    /** 用户在 9 格编辑页手工填的股票名，代码不在池中时的兜底名称。 */
    private String userStockName;
    private String reason;
    /** 该格对应的参考截图 URL（由 admin 上传，用于记录候选股截图），可空。 */
    private String imageUrl;
    private LocalDateTime updatedAt;
}
