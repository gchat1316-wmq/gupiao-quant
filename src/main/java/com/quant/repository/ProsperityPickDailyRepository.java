package com.quant.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.ProsperityPickDaily;

public interface ProsperityPickDailyRepository extends JpaRepository<ProsperityPickDaily, Integer> {

  List<ProsperityPickDaily> findBySnapDateOrderByCombinedScoreDesc(LocalDate snapDate);

  Optional<ProsperityPickDaily> findBySnapDateAndStockCode(LocalDate snapDate, String stockCode);

  void deleteBySnapDate(LocalDate snapDate);

  Optional<ProsperityPickDaily> findFirstByOrderBySnapDateDesc();

  List<ProsperityPickDaily> findBySnapDateBetweenOrderBySnapDateDescCombinedScoreDesc(
      LocalDate from, LocalDate to);
}
