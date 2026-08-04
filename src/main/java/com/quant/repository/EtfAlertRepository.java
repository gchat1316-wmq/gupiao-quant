package com.quant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.EtfAlert;

public interface EtfAlertRepository extends JpaRepository<EtfAlert, Long> {

  Optional<EtfAlert> findFirstByStockCodeAndSignalTypeOrderByTriggerAtDesc(
      String stockCode, String signalType);

  Optional<EtfAlert> findFirstBySignalTypeOrderByTriggerAtDesc(String signalType);

  List<EtfAlert> findTop50ByOrderByTriggerAtDesc();
}
