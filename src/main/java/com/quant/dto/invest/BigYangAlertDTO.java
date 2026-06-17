package com.quant.dto.invest;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class BigYangAlertDTO {
    private Long id;
    private String stockCode;
    private String stockName;
    private String title;
    private String content;
    private BigDecimal triggerPrice;
    private LocalDateTime triggerAt;
    private boolean read;
}
