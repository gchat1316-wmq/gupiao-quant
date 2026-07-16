package com.quant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "study_quiz_record")
public class StudyQuizRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "quiz_id", nullable = false)
  private Long quizId;

  @Column(length = 8)
  private String picked;

  private Integer correct;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
