package com.quant.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.InvestBigYangSignal;

public interface InvestBigYangSignalRepository extends JpaRepository<InvestBigYangSignal, Long> {

  boolean existsByStockCodeAndSignalStatus(String stockCode, String signalStatus);

  Optional<InvestBigYangSignal> findByStockCodeAndSignalStatus(
      String stockCode, String signalStatus);

  Optional<InvestBigYangSignal> findByStockCodeAndFirstLimitUpDate(
      String stockCode, LocalDate firstLimitUpDate);

  List<InvestBigYangSignal> findTop200ByOrderByUpdatedAtDescIdDesc();

  List<InvestBigYangSignal> findTop200BySignalStatusOrderByUpdatedAtDescIdDesc(String signalStatus);

  long countBySignalStatus(String signalStatus);

  long countBySignalStatusAndCreatedAtGreaterThanEqual(String signalStatus, LocalDateTime from);

  long countBySignalStatusAndTriggerDate(String signalStatus, LocalDate triggerDate);
}
