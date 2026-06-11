package com.quant.repository;

import com.quant.entity.InvestPositionFill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvestPositionFillRepository extends JpaRepository<InvestPositionFill, Long> {

    List<InvestPositionFill> findByPoolIdOrderByFilledAtAscIdAsc(Integer poolId);

    List<InvestPositionFill> findByPoolIdOrderByFilledAtDescIdDesc(Integer poolId);

    void deleteByPoolId(Integer poolId);
}
