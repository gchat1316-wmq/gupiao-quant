package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.TechAiPositionFill;

public interface TechAiPositionFillRepository extends JpaRepository<TechAiPositionFill, Long> {

  List<TechAiPositionFill> findByPoolIdOrderByFilledAtAscIdAsc(Integer poolId);

  List<TechAiPositionFill> findByPoolIdOrderByFilledAtDescIdDesc(Integer poolId);

  void deleteByPoolId(Integer poolId);
}
