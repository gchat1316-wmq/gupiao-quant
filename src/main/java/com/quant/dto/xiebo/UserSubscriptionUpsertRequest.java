package com.quant.dto.xiebo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UserSubscriptionUpsertRequest {

    @NotNull
    private Boolean enabled;

    /** 关注|建仓|减仓|清仓 — enabled=true 时必填 */
    @Pattern(regexp = "^(关注|建仓|减仓|清仓)$",
             message = "status 必须是 关注|建仓|减仓|清仓 之一")
    private String status;

    @DecimalMin(value = "0.01", message = "买入价必须 > 0")
    private BigDecimal priceBuy;

    @DecimalMin(value = "0.01", message = "止损价必须 > 0")
    private BigDecimal priceStopLoss;

    @DecimalMin(value = "0.01", message = "加仓价必须 > 0")
    private BigDecimal priceAddPosition;

    @DecimalMin(value = "0.01", message = "减仓价必须 > 0")
    private BigDecimal priceReducePosition;

    @DecimalMin(value = "0.01", message = "清仓价必须 > 0")
    private BigDecimal priceClearPosition;

    @Size(max = 64, message = "SCKEY 长度不能超过 64")
    private String serverchanSendKey;
}
