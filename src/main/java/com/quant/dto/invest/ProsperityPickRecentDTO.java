package com.quant.dto.invest;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class ProsperityPickRecentDTO {
    private Long id;
    private String stockCode;
    private String stockName;
    private LocalDate analysisDate;
    private String imageUrl;
}
