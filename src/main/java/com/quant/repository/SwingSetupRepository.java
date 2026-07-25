package com.quant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.SwingSetup;

public interface SwingSetupRepository extends JpaRepository<SwingSetup, Long> {

  List<SwingSetup> findByWatchIdAndStatusInOrderByDetectedAtDesc(
      Long watchId, Collection<String> statuses);

  Optional<SwingSetup> findFirstByWatchIdAndSetupTypeAndStatusOrderByDetectedAtDesc(
      Long watchId, String setupType, String status);

  List<SwingSetup> findByWatchIdOrderByDetectedAtDesc(Long watchId);
}
