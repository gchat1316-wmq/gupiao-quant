package com.quant.dto.invest;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ProsperityResultDTO {
    private int requested;
    private int matched;
    private List<String> notFound;
    /** 板块景气综合判断: HIGH / MIXED / LOW */
    private String sectorLevel;
    /** 板块判断文字 */
    private String sectorSummary;
    /** 所有季度的统一时间轴（升序） */
    private List<String> quarterAxis;
    private List<ProsperityStockDTO> stocks;
}
