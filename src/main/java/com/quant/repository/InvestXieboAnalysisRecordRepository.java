package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.InvestXieboAnalysisRecord;

public interface InvestXieboAnalysisRecordRepository
    extends JpaRepository<InvestXieboAnalysisRecord, Long> {
  List<InvestXieboAnalysisRecord> findAllByOrderByIdDesc();
}
