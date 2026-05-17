package com.quant.dto.study;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class QuizDTO {
    private Long id;
    private String stem;
    private List<QuizOption> options;
    private String relatedNodeTitle;

    @Data
    @Builder
    public static class QuizOption {
        private String key;
        private String text;
    }
}
