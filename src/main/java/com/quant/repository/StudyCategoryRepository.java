package com.quant.repository;

import com.quant.entity.StudyCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudyCategoryRepository extends JpaRepository<StudyCategory, Integer> {
    List<StudyCategory> findAllByOrderBySortAsc();
}
