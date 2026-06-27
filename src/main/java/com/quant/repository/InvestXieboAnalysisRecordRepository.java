package com.quant.repository;

import com.quant.entity.InvestXieboAnalysisRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvestXieboAnalysisRecordRepository extends JpaRepository<InvestXieboAnalysisRecord, Long> {
    List<InvestXieboAnalysisRecord> findAllByOrderByIdDesc();
}
