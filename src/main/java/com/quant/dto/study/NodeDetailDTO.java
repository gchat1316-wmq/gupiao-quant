package com.quant.dto.study;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NodeDetailDTO {
  private KnowledgeNodeDTO node;
  private List<CardDTO> aiDetailCards;
  private List<CardDTO> flashCards;
  private int quizCount;
  private Long courseId;
  private String courseTitle;
}
