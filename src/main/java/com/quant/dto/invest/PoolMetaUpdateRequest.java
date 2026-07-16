package com.quant.dto.invest;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PoolMetaUpdateRequest {
  private String displayName;
  private String valuationMethodMd;
  private String weeklyOpportunityMd;
  private Integer displayOrder;
}
