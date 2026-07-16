package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.StudyKnowledgeNode;

public interface StudyKnowledgeNodeRepository extends JpaRepository<StudyKnowledgeNode, Long> {
  List<StudyKnowledgeNode> findByCourseIdOrderByLevelAscSortAscIdAsc(Long courseId);
}
