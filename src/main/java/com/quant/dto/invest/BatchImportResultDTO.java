package com.quant.dto.invest;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class BatchImportResultDTO {
    private int imported;
    private int skipped;
    private int failed;
    private List<String> failures;
}
