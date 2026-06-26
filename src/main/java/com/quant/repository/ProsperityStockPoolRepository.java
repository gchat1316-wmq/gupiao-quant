package com.quant.repository;

import com.quant.entity.ProsperityStockPool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProsperityStockPoolRepository extends JpaRepository<ProsperityStockPool, Integer> {

    Optional<ProsperityStockPool> findByStockCode(String stockCode);

    List<ProsperityStockPool> findAllByOrderByLastAddedAtDesc();

    /** 测试 / 手动清理用 */
    long deleteByStockCode(String stockCode);
}
