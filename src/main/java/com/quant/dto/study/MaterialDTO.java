package com.quant.dto.study;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MaterialDTO {
  private Long id;
  private String fileName;
  private String fileType;
  private Long size;
  private String parseStatus;
  private Integer progress;
}
