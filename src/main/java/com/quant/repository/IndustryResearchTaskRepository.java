package com.quant.repository;

import com.quant.entity.IndustryResearchTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IndustryResearchTaskRepository extends JpaRepository<IndustryResearchTask, Long> {

    List<IndustryResearchTask> findByCategoryIdOrderByCreatedAtDesc(Long categoryId);

    List<IndustryResearchTask> findByStatusOrderByCreatedAtDesc(String status);

    List<IndustryResearchTask> findTop20ByOrderByCreatedAtDesc();
}