package com.quant.dto.xiebo;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ServerchanKeyUpdateRequest {

    @Size(max = 64, message = "SCKEY 长度不能超过 64")
    private String serverchanSendKey;
}
