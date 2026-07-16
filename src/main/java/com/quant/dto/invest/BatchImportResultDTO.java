package com.quant.dto.invest;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BatchImportResultDTO {
  private int imported;
  private int skipped;
  private int failed;
  private List<String> failures;
}
