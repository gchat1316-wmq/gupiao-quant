package com.quant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "study_quiz")
public class StudyQuiz {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "node_id", nullable = false)
  private Long nodeId;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String stem;

  @Column(name = "options_json", nullable = false, columnDefinition = "TEXT")
  private String optionsJson;

  @Column(nullable = false, length = 8)
  private String answer;

  @Column(columnDefinition = "TEXT")
  private String analysis;

  private Integer sort;
}
