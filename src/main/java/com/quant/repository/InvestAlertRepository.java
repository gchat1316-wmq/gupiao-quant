package com.quant.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.InvestAlert;

public interface InvestAlertRepository extends JpaRepository<InvestAlert, Long> {

  boolean existsByStockCodeAndSignalTypeAndTriggerAtBetween(
      String stockCode, String signalType, LocalDateTime from, LocalDateTime to);

  Optional<InvestAlert> findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(
      String stockCode, String signalType);

  List<InvestAlert> findTop100ByStockCodeInOrderByTriggerAtDesc(List<String> stockCodes);

  List<InvestAlert> findTop100BySignalTypeOrderByTriggerAtDesc(String signalType);

  long countBySignalTypeAndReadFlag(String signalType, Integer readFlag);
}
