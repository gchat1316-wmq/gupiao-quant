package com.quant.repository;

import com.quant.entity.InvestPositionFill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface InvestPositionFillRepository extends JpaRepository<InvestPositionFill, Long> {

    List<InvestPositionFill> findByPoolIdOrderByFilledAtAscIdAsc(Integer poolId);

    List<InvestPositionFill> findByPoolIdOrderByFilledAtDescIdDesc(Integer poolId);

    void deleteByPoolId(Integer poolId);

    @Query("SELECT f FROM InvestPositionFill f WHERE f.filledAt >= :since ORDER BY f.filledAt DESC")
    List<InvestPositionFill> findRecentSince(@Param("since") LocalDateTime since);
}
