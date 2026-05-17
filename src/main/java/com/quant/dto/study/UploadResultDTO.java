package com.quant.dto.study;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UploadResultDTO {
    private Long courseId;
    private String title;
    private String status;
    private Integer progress;
    private String message;
}
