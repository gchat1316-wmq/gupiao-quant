package com.quant.repository;

import com.quant.entity.InvestProsperityPick;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface InvestProsperityPickRepository extends JpaRepository<InvestProsperityPick, Long> {

    Optional<InvestProsperityPick> findByStockCodeAndAnalysisDate(String stockCode, LocalDate analysisDate);

    List<InvestProsperityPick> findTop10ByOrderByAnalysisDateDescIdDesc();

    List<InvestProsperityPick> findTop30ByAnalysisDateGreaterThanEqualOrderByAnalysisDateDescIdDesc(LocalDate analysisDate);
}
