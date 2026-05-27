package com.quant.dto.invest;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BatchImportRequest {
    private List<OcrParsedItemDTO> items;
}
