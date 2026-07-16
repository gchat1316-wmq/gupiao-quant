package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.PotentialPositionFill;

public interface PotentialPositionFillRepository
    extends JpaRepository<PotentialPositionFill, Long> {

  List<PotentialPositionFill> findByPoolIdOrderByFilledAtAscIdAsc(Integer poolId);

  List<PotentialPositionFill> findByPoolIdOrderByFilledAtDescIdDesc(Integer poolId);

  void deleteByPoolId(Integer poolId);
}
