package com.quant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.MoneySetup;

public interface MoneySetupRepository extends JpaRepository<MoneySetup, Long> {

  List<MoneySetup> findByWatchIdOrderByIdDesc(Long watchId);

  List<MoneySetup> findByWatchIdAndStatus(Long watchId, String status);

  Optional<MoneySetup> findFirstByWatchIdAndSetupTypeAndStatus(
      Long watchId, String setupType, String status);

  List<MoneySetup> findByWatchIdInAndStatus(Collection<Long> watchIds, String status);
}
