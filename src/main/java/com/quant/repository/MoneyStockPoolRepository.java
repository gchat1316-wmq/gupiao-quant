package com.quant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.MoneyStockPool;

public interface MoneyStockPoolRepository extends JpaRepository<MoneyStockPool, Long> {

  Optional<MoneyStockPool> findByUserIdAndStockCode(Long userId, String stockCode);

  List<MoneyStockPool> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, String status);

  List<MoneyStockPool> findByStatusOrderByUpdatedAtDesc(String status);

  long countByUserIdAndStatus(Long userId, String status);
}
