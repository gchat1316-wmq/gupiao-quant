package com.quant.dto.study;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class HomeDataDTO {
    private List<CourseSummaryDTO> myCourses;
    private List<CourseSummaryDTO> publicCourses;
    private List<CategoryDTO> categories;
    private MyCourseTabCountsDTO myCounts;
}
