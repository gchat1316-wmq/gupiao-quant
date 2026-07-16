package com.quant.dto.study;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MyCourseTabCountsDTO {
  private int all;
  private int created;
  private int learning;
  private int pending;
  private int done;
}
