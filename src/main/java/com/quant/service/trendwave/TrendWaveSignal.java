package com.quant.service.trendwave;

import java.math.BigDecimal;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TrendWaveSignal {
  private String eventType;
  private String severity; // INFO | WARN | ACTION
  private String title;
  private String content;
  private BigDecimal triggerPrice;
  private Map<String, Object> triggerData;
  private Long setupId;
  private boolean mutateState;
  private String nextWatchStatus;
  private String nextSetupStatus;
  private String nextPositionStatus;
  private BigDecimal nextPositionPct;
  private String closeReason;
  private boolean paperAutoExecute;
}
