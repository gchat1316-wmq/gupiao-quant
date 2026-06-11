package com.quant.repository;

import com.quant.entity.PotentialPool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PotentialPoolRepository extends JpaRepository<PotentialPool, Integer> {

    Optional<PotentialPool> findByStockCode(String stockCode);

    List<PotentialPool> findAllByOrderByCreatedAtDesc();

    List<PotentialPool> findByStatusNotOrderByCreatedAtDesc(String status);
}
