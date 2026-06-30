package com.quant.repository;

import com.quant.entity.InvestStockPool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InvestStockPoolRepository extends JpaRepository<InvestStockPool, Integer> {

    Optional<InvestStockPool> findByStockCode(String stockCode);

    List<InvestStockPool> findByStockCodeIn(Collection<String> stockCodes);

    List<InvestStockPool> findAllByOrderByCreatedAtDesc();

    List<InvestStockPool> findByPoolTypeOrderByCreatedAtDesc(String poolType);

    long countByPoolType(String poolType);

    void deleteByPoolType(String poolType);

    @Modifying
    @Query("DELETE FROM InvestStockPool p WHERE p.poolType = :poolType OR UPPER(p.stockCode) IN :upperCodes")
    int deleteByPoolTypeOrUpperStockCodeIn(@Param("poolType") String poolType,
                                           @Param("upperCodes") Collection<String> upperCodes);
}
