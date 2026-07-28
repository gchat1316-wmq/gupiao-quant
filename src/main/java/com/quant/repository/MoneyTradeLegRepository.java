package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.MoneyTradeLeg;

public interface MoneyTradeLegRepository extends JpaRepository<MoneyTradeLeg, Long> {

  List<MoneyTradeLeg> findByPositionIdOrderByTradeDateAsc(Long positionId);

  List<MoneyTradeLeg> findByStockCodeOrderByTradeDateDesc(String stockCode);
}
