package com.quant.repository;

import com.quant.entity.InvestPositionCommon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 三池持仓状态聚合表 Repository。
 *
 * @see InvestPositionCommon
 */
@Repository
public interface InvestPositionCommonRepository extends JpaRepository<InvestPositionCommon, String> {

    Optional<InvestPositionCommon> findByStockCodeAndPoolType(String stockCode, String poolType);

    List<InvestPositionCommon> findByStockCodeIn(Collection<String> stockCodes);

    List<InvestPositionCommon> findByPoolType(String poolType);

    void deleteByPoolType(String poolType);
}
