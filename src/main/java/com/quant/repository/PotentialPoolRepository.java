package com.quant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.PotentialPool;

public interface PotentialPoolRepository extends JpaRepository<PotentialPool, Integer> {

  Optional<PotentialPool> findByStockCode(String stockCode);

  List<PotentialPool> findAllByOrderByCreatedAtDesc();

  List<PotentialPool> findByStatusNotOrderByCreatedAtDesc(String status);
}
