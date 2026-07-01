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

    /**
     * 单条更新 displayOrder。批量调用由 service 在事务内循环,简单可靠。
     * 用 JPQL 显式赋值而不是 dirty checking,可避免 Hibernate 多次 select 后的额外开销。
     */
    @Modifying
    @Query("UPDATE InvestStockPool p SET p.displayOrder = :displayOrder WHERE p.id = :id")
    int updateDisplayOrder(@Param("id") Integer id,
                           @Param("displayOrder") Integer displayOrder);
}
