package com.quant.dto.study;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuizAnswerDTO {
  private Long quizId;
  private String correctAnswer;
  private String picked;
  private boolean correct;
  private String analysis;
}
