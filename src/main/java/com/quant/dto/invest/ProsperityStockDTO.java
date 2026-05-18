package com.quant.dto.invest;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ProsperityStockDTO {
    private String stockCode;
    private String stockName;
    /** 最新季度景气等级 */
    private String latestLevel;
    private List<ProsperityQuarterDTO> quarters;
}
