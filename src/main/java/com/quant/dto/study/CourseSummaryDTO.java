package com.quant.dto.study;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CourseSummaryDTO {
  private Long id;
  private String title;
  private String summary;
  private String coverText;
  private String coverColor;
  private String owner;
  private String visibility;
  private String status;
  private Integer progress;
  private String learnStatus;
  private Integer masteredCnt;
  private Integer totalCnt;
  private Integer learnerCnt;
  private Integer categoryId;
}
