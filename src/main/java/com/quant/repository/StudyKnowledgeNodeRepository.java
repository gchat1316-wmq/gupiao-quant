package com.quant.repository;

import com.quant.entity.StudyKnowledgeNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudyKnowledgeNodeRepository extends JpaRepository<StudyKnowledgeNode, Long> {
    List<StudyKnowledgeNode> findByCourseIdOrderByLevelAscSortAscIdAsc(Long courseId);
}
