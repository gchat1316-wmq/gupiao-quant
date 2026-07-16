package com.quant.dto.prosperitystrong;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class HotSectorDTO {
  private Integer id;
  private LocalDate snapDate;
  private String sectorCode;
  private String sectorName;
  private Integer rankNo;
  private BigDecimal change1d;
  private BigDecimal change5d;
  private BigDecimal change20d;
  private BigDecimal capitalInflow5d;
  private Integer upCount;
  private Integer downCount;
  private String leadStock;
  private BigDecimal leadStockChange;
  private Integer persistenceDays;
  private BigDecimal score;
  private String aiNarrative;
  private String dataSource;
  private List<LeaderCandidateDTO> leaders;
  private Integer matchedMemberCount;
  private Integer quotedMemberCount;
  private String diagnosticMessage;
}
