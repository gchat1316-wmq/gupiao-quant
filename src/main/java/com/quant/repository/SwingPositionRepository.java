package com.quant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.SwingPosition;

public interface SwingPositionRepository extends JpaRepository<SwingPosition, Long> {

  Optional<SwingPosition> findFirstByWatchIdAndStatusInOrderByEntryTimeDesc(
      Long watchId, Collection<String> statuses);

  List<SwingPosition> findByUserIdAndStatusInOrderByEntryTimeDesc(
      Long userId, Collection<String> statuses);

  List<SwingPosition> findByUserIdOrderByEntryTimeDesc(Long userId);

  Optional<SwingPosition> findByIdAndUserId(Long id, Long userId);

  long countByUserIdAndStatusIn(Long userId, Collection<String> statuses);
}
