package com.quant.dto.marketrecap;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketRecapPageDTO {
  private List<String> markets;
  private String selectedMarket;
  private MarketRecapDetailDTO latest;
  private List<MarketRecapSummaryDTO> timeline;
}
