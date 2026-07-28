package com.quant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.MoneyPosition;

public interface MoneyPositionRepository extends JpaRepository<MoneyPosition, Long> {

  Optional<MoneyPosition> findByWatchId(Long watchId);

  List<MoneyPosition> findByStatusIn(Collection<String> statuses);

  List<MoneyPosition> findByUserIdAndStatusInOrderByUpdatedAtDesc(
      Long userId, Collection<String> statuses);

  List<MoneyPosition> findByStatusOrderByClosedAtDesc(String status);

  long countByUserIdAndStatusIn(Long userId, Collection<String> statuses);
}
