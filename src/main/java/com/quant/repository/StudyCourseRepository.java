package com.quant.repository;

import com.quant.entity.StudyCourse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudyCourseRepository extends JpaRepository<StudyCourse, Long> {
    List<StudyCourse> findByVisibilityOrderByIdAsc(String visibility);

    List<StudyCourse> findByVisibilityAndCategoryIdOrderByIdAsc(String visibility, Integer categoryId);
}
