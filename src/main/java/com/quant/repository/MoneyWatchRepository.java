package com.quant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.MoneyWatch;

public interface MoneyWatchRepository extends JpaRepository<MoneyWatch, Long> {

  Optional<MoneyWatch> findByPoolIdAndActiveFlag(Long poolId, Integer activeFlag);

  List<MoneyWatch> findByActiveFlagAndStatusIn(Integer activeFlag, Collection<String> statuses);

  List<MoneyWatch> findByUserIdAndActiveFlagOrderByUpdatedAtDesc(Long userId, Integer activeFlag);

  List<MoneyWatch> findByActiveFlagOrderByUpdatedAtDesc(Integer activeFlag);

  long countByUserIdAndActiveFlagAndStatusIn(
      Long userId, Integer activeFlag, Collection<String> statuses);

  Optional<MoneyWatch> findByIdAndUserId(Long id, Long userId);
}
