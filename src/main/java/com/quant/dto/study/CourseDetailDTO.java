package com.quant.dto.study;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CourseDetailDTO {
  private CourseSummaryDTO course;
  private List<KnowledgeNodeDTO> tree;
  private List<MaterialDTO> materials;
}
