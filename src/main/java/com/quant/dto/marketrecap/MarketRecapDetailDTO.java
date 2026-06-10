package com.quant.dto.marketrecap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketRecapDetailDTO {
    private Long id;
    private String market;
    private String tradeDate;
    private String title;
    private String indexesSummary;
    private String advanceDecline;
    private Integer limitUp;
    private Integer limitDown;
    private String sentiment;
    private String summaryExcerpt;
    private List<SectorCardDTO> sectors;
    private List<String> risks;
    private List<String> catalysts;
    private List<KeyDataItemDTO> keyData;
    private List<StrategyItemDTO> nextDayStrategy;
    private String contentHtml;
}
