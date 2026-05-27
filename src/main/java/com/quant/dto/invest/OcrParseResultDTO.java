package com.quant.dto.invest;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class OcrParseResultDTO {
    private int totalParsed;
    private int matched;
    private List<OcrParsedItemDTO> items;
    private String rawAiText;
}
