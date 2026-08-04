package com.quant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.EtfPool;

public interface EtfPoolRepository extends JpaRepository<EtfPool, Long> {

  Optional<EtfPool> findByStockCode(String stockCode);

  List<EtfPool> findByStatusOrderByIdAsc(String status);

  long countByStatus(String status);
}
