package com.quant.dto.prosperitystrong;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PipelineRunResultDTO {
  private LocalDate snapDate;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private long durationMs;
  private int sectorCount;
  private int leaderCount;
  private int hardFilteredCount;
  private int candidateCount;
  private String provider;
  private String providerMessage;
  private String status; // SUCCESS / PARTIAL / FAILED
  private String message;
}
