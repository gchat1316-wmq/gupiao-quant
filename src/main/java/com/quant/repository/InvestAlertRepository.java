package com.quant.repository;

import com.quant.entity.InvestAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InvestAlertRepository extends JpaRepository<InvestAlert, Long> {

    boolean existsByStockCodeAndSignalTypeAndTriggerAtBetween(
            String stockCode, String signalType, LocalDateTime from, LocalDateTime to);

    Optional<InvestAlert> findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(String stockCode, String signalType);

    List<InvestAlert> findTop100ByStockCodeInOrderByTriggerAtDesc(List<String> stockCodes);

    List<InvestAlert> findTop100BySignalTypeOrderByTriggerAtDesc(String signalType);

    long countBySignalTypeAndReadFlag(String signalType, Integer readFlag);
}
