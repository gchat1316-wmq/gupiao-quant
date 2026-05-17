package com.quant.dto.study;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CourseDetailDTO {
    private CourseSummaryDTO course;
    private List<KnowledgeNodeDTO> tree;
    private List<MaterialDTO> materials;
}
