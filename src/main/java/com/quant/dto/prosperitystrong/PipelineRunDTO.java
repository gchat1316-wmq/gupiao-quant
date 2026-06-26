package com.quant.dto.prosperitystrong;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
