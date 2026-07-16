package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.StudyCategory;

public interface StudyCategoryRepository extends JpaRepository<StudyCategory, Integer> {
  List<StudyCategory> findAllByOrderBySortAsc();
}
