package com.quant.dto.prosperitystrong;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProsperityPoolItemDTO {

  private Integer id;
  private String stockCode;
  private String stockName;
  private String status;
  private Integer poolCount;
  private LocalDateTime firstAddedAt;
  private LocalDateTime lastAddedAt;
  private LocalDate lastSnapDate;
  private String sectorName;
  private BigDecimal combinedScore;
  private BigDecimal latestPrice;
  private BigDecimal buyLeftPrice;
  private BigDecimal sellTarget1;
  private BigDecimal stopLossPrice;
  private BigDecimal corePositionPct;
  private BigDecimal tacticalPositionPct;
  private String actionSignal;
  private String memo;
  private Long ownerId; // NULL 表示系统共享池
}
