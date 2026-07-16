package com.quant.dto.study;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KnowledgeNodeDTO {
  private Long id;
  private Long parentId;
  private String title;
  private String summary;
  private String definition;
  private Integer level;
  private Integer mastered;
  private List<KnowledgeNodeDTO> children;
}
