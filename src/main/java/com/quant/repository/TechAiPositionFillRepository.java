package com.quant.repository;

import com.quant.entity.TechAiPositionFill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TechAiPositionFillRepository extends JpaRepository<TechAiPositionFill, Long> {

    List<TechAiPositionFill> findByPoolIdOrderByFilledAtAscIdAsc(Integer poolId);

    List<TechAiPositionFill> findByPoolIdOrderByFilledAtDescIdDesc(Integer poolId);

    void deleteByPoolId(Integer poolId);
}