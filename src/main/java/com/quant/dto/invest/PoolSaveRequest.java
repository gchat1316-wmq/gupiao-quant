package com.quant.dto.invest;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PoolSaveRequest {
    private String keyword;
    private String poolType;
    private String memo;
    private BigDecimal targetPrice;
    private String status;
}
