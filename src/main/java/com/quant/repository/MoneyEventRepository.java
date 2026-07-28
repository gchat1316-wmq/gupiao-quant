package com.quant.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.MoneyEvent;

public interface MoneyEventRepository extends JpaRepository<MoneyEvent, Long> {

  Optional<MoneyEvent> findFirstByWatchIdAndEventTypeOrderByCreatedAtDesc(
      Long watchId, String eventType);

  Optional<MoneyEvent> findFirstByStockCodeAndEventTypeOrderByCreatedAtDesc(
      String stockCode, String eventType);

  List<MoneyEvent> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
      LocalDateTime from, Pageable pageable);

  List<MoneyEvent> findTop100ByOrderByCreatedAtDesc();

  List<MoneyEvent> findByAcknowledgedOrderByCreatedAtDesc(Integer acknowledged, Pageable pageable);

  boolean existsByWatchIdAndEventTypeAndCreatedAtBetween(
      Long watchId, String eventType, LocalDateTime from, LocalDateTime to);
}
