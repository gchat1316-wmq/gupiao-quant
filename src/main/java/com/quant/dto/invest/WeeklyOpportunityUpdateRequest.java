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
        /** 用户在编辑页手工填的股票名（代码不在池中时的兜底名称），空字符串视作 null */
        private String userStockName;
        private String reason;
        /** 该格参考截图 URL，空字符串视作 null（前端不传则保持原值不动） */
        private String imageUrl;
    }
}
