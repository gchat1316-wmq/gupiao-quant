package com.quant.dto.invest;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PoolFieldUpdateRequest {
    /** 字段名（驼峰），与 PoolSaveRequest 字段一致 */
    private String field;
    /** 字段值（字符串，由后端按字段类型解析） */
    private String value;
}
