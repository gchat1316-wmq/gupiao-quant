package com.quant.dto.prosperitystrong;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PipelineRunDTO {
  private Integer id;
  private LocalDate snapDate;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private Long durationMs;
  private String status;
  private String message;
  private String provider;
  private Integer sectorCount;
  private Integer leaderCount;
  private Integer hardFilteredCount;
  private Integer candidateCount;
}
