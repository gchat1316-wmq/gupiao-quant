package com.quant.dto.study;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HomeDataDTO {
  private List<CourseSummaryDTO> myCourses;
  private List<CourseSummaryDTO> publicCourses;
  private List<CategoryDTO> categories;
  private MyCourseTabCountsDTO myCounts;
}
