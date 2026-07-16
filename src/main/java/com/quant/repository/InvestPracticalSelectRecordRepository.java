package com.quant.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.quant.entity.InvestPracticalSelectRecord;

public interface InvestPracticalSelectRecordRepository
    extends JpaRepository<InvestPracticalSelectRecord, Long> {

  Optional<InvestPracticalSelectRecord> findByShareToken(String shareToken);

  Page<InvestPracticalSelectRecord> findAllByOrderByIdDesc(Pageable pageable);

  @Query(
      "SELECT r FROM InvestPracticalSelectRecord r WHERE "
          + "(:kw IS NULL OR :kw = '' OR r.stockCode LIKE %:kw% OR r.stockName LIKE %:kw%) "
          + "ORDER BY r.id DESC")
  Page<InvestPracticalSelectRecord> search(@Param("kw") String kw, Pageable pageable);
}
