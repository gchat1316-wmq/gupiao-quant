package com.quant.repository;

import com.quant.entity.TechAiPool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TechAiPoolRepository extends JpaRepository<TechAiPool, Integer> {

    Optional<TechAiPool> findByStockCode(String stockCode);

    List<TechAiPool> findAllByOrderByCreatedAtDesc();

    List<TechAiPool> findByStatusNotOrderByCreatedAtDesc(String status);
}