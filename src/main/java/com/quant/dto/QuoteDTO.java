package com.quant.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteDTO {
  private Long id;
  private String content;
  private String author;
  private String source;
  private String tags;
  private Integer likes;
  private Long importedNodeId;
  private LocalDateTime createdAt;
}
