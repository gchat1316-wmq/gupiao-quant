package com.quant.dto.xieboinvest;

import lombok.Data;

import java.util.List;

/** 一次性更新谢博投资某分类 9 个 slot 的请求体。 */
@Data
public class XieboWeeklyOpportunityUpdateRequest {
    private List<SlotItem> slots;

    @Data
    public static class SlotItem {
        private Integer slotIndex;
        private String stockCode;
        private String reason;
    }
}
