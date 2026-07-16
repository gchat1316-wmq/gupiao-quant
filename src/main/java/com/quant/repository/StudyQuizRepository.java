package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.StudyQuiz;

public interface StudyQuizRepository extends JpaRepository<StudyQuiz, Long> {
  List<StudyQuiz> findByNodeIdOrderBySortAscIdAsc(Long nodeId);
}
