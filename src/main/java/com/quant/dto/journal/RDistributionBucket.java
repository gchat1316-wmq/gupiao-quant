package com.quant.dto.journal;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RDistributionBucket {
    private String label;
    private Long count;
}