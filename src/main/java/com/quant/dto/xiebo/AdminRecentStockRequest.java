package com.quant.dto.xiebo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminRecentStockRequest {

    @NotBlank
    @Size(max = 16)
    private String stockCode;

    @NotBlank
    @Size(max = 64)
    private String stockName;

    /** 科技AI|创新药|质量优选 */
    @NotBlank
    @Pattern(regexp = "^(科技AI|创新药|质量优选)$",
             message = "type 必须是 科技AI|创新药|质量优选 之一")
    private String type;
}
