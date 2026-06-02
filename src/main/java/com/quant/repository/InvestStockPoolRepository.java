package com.quant.repository;

import com.quant.entity.InvestStockPool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvestStockPoolRepository extends JpaRepository<InvestStockPool, Integer> {

    Optional<InvestStockPool> findByStockCode(String stockCode);

    List<InvestStockPool> findAllByOrderByCreatedAtDesc();

    List<InvestStockPool> findByPoolTypeOrderByCreatedAtDesc(String poolType);

    List<InvestStockPool> findByPoolTypeAndStatusNotOrderByCreatedAtDesc(String poolType, String status);
}
