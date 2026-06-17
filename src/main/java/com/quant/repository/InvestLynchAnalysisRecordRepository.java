package com.quant.repository;

import com.quant.entity.InvestLynchAnalysisRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvestLynchAnalysisRecordRepository extends JpaRepository<InvestLynchAnalysisRecord, Long> {
    List<InvestLynchAnalysisRecord> findAllByOrderByIdDesc();
}
