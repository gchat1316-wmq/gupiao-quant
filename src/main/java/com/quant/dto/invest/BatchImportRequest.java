package com.quant.dto.invest;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BatchImportRequest {
  private List<OcrParsedItemDTO> items;
}
