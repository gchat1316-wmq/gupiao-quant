package com.quant.dto.trendwave;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MoneyWatchDTO {
  private Long id;
  private Long poolId;
  private String stockCode;
  private String stockName;
  private String status;
  private String sectorTag;
  private Boolean screenPassed;
  private Map<String, Object> screenDetail;
  private String marketRegime;
  private Boolean indexAboveMa20;
  private String buySignalType;
  private LocalDateTime buySignalAt;
  private BigDecimal buySignalPrice;
  private LocalDateTime signalExpireAt;
  private String invalidReason;
  private Boolean paperMode;
  private String source;
  private BigDecimal latestPrice;
  private BigDecimal dailyChangePct;
  private BigDecimal ma5;
  private BigDecimal ma10;
  private BigDecimal ma20;
  private BigDecimal ma60;
  private List<MoneySetupDTO> setups;
  private MoneyPositionDTO position;
  private LocalDateTime updatedAt;
}
