package com.quant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "study_course")
public class StudyCourse {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String summary;

  @Column(name = "cover_text", length = 200)
  private String coverText;

  @Column(name = "cover_color", length = 20)
  private String coverColor;

  @Column(length = 64)
  private String owner;

  @Column(name = "source_type", length = 20)
  private String sourceType;

  @Column(length = 10)
  private String visibility;

  @Column(length = 20)
  private String status;

  private Integer progress;

  @Column(name = "category_id")
  private Integer categoryId;

  @Column(name = "learn_status", length = 20)
  private String learnStatus;

  @Column(name = "mastered_cnt")
  private Integer masteredCnt;

  @Column(name = "total_cnt")
  private Integer totalCnt;

  @Column(name = "learner_cnt")
  private Integer learnerCnt;

  @Column(name = "book_cover_url", length = 500)
  private String bookCoverUrl;

  @Column(name = "recommend_images", columnDefinition = "TEXT")
  private String recommendImages;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
