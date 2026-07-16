package com.quant.dto.invest;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OcrParseResultDTO {
  private int totalParsed;
  private int matched;
  private List<OcrParsedItemDTO> items;
  private String rawAiText;
}
