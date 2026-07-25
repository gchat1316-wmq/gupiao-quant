package com.quant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.SwingSignal;

public interface SwingSignalRepository extends JpaRepository<SwingSignal, Long> {

  Optional<SwingSignal> findByDedupeKey(String dedupeKey);

  List<SwingSignal> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);

  List<SwingSignal> findTop20ByWatchIdOrderByCreatedAtDesc(Long watchId);

  Optional<SwingSignal> findByIdAndUserId(Long id, Long userId);
}
