package com.quant.repository;

import com.quant.entity.PotentialPositionFill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PotentialPositionFillRepository extends JpaRepository<PotentialPositionFill, Long> {

    List<PotentialPositionFill> findByPoolIdOrderByFilledAtAscIdAsc(Integer poolId);

    List<PotentialPositionFill> findByPoolIdOrderByFilledAtDescIdDesc(Integer poolId);

    void deleteByPoolId(Integer poolId);
}
