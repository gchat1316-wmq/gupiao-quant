package com.quant.dto.invest;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OcrImportRequest {
    /** data:image/png;base64,... 或纯 base64 */
    private String imageBase64;
    /** 默认分类，识别条目未明确时使用：quality / tech_vc */
    private String defaultPoolType;
}
