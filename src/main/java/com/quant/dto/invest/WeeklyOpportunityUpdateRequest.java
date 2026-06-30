package com.quant.dto.invest;

import lombok.Data;

import java.util.List;

/**
 * 一次性更新 9 个 slot 的请求体。
 * 服务端校验：slots.size() == 9、slotIndex ∈ [0,8]、slotIndex 不重复。
 */
@Data
public class WeeklyOpportunityUpdateRequest {

    private List<SlotItem> slots;

    @Data
    public static class SlotItem {
        private Integer slotIndex;
        /** 空字符串视作 null */
        private String stockCode;
        private String reason;
    }
}
