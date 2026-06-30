package com.quant.dto.monitor;

import lombok.Data;

/**
 * 添加监控的请求体。code/poolType 必填；若该 (code, poolType) 在 invest_position_common
 * 不存在则同步创建空记录 (status=watching)，监控字段全部默认 0/null。
 */
@Data
public class MonitorAddRequest {
    private String stockCode;
    private String poolType;       // 'tech_ai' | 'potential' | 'stock'
    private String stockName;
    private String memo;
}
