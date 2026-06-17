package com.quant.repository;

import com.quant.entity.InvestBigYangSignal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InvestBigYangSignalRepository extends JpaRepository<InvestBigYangSignal, Long> {

    boolean existsByStockCodeAndSignalStatus(String stockCode, String signalStatus);

    Optional<InvestBigYangSignal> findByStockCodeAndFirstLimitUpDate(String stockCode, LocalDate firstLimitUpDate);

    List<InvestBigYangSignal> findTop200ByOrderByUpdatedAtDescIdDesc();

    List<InvestBigYangSignal> findTop200BySignalStatusOrderByUpdatedAtDescIdDesc(String signalStatus);

    long countBySignalStatus(String signalStatus);

    long countBySignalStatusAndCreatedAtGreaterThanEqual(String signalStatus, LocalDateTime from);

    long countBySignalStatusAndTriggerDate(String signalStatus, LocalDate triggerDate);
}
