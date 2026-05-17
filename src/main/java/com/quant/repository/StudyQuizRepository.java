package com.quant.repository;

import com.quant.entity.StudyQuiz;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudyQuizRepository extends JpaRepository<StudyQuiz, Long> {
    List<StudyQuiz> findByNodeIdOrderBySortAscIdAsc(Long nodeId);
}
