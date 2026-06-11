package com.quant.dto.invest;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class PositionFillRequest {
    /** open / add / reduce / clear */
    private String action;
    private BigDecimal price;
    private BigDecimal lots;
    private BigDecimal fee;
    private String note;
    private LocalDateTime filledAt;
}
