package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.StudyCourse;

public interface StudyCourseRepository extends JpaRepository<StudyCourse, Long> {
  List<StudyCourse> findByVisibilityOrderByIdAsc(String visibility);

  List<StudyCourse> findByVisibilityAndCategoryIdOrderByIdAsc(
      String visibility, Integer categoryId);
}
