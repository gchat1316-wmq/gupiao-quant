package com.quant.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.EtfDailyKline;

public interface EtfDailyKlineRepository extends JpaRepository<EtfDailyKline, Long> {

  List<EtfDailyKline> findTop60ByStockCodeOrderByTradeDateDesc(String stockCode);

  Optional<EtfDailyKline> findByStockCodeAndTradeDate(String stockCode, LocalDate tradeDate);
}
