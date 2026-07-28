package com.quant.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.MoneyDailyMetrics;

public interface MoneyDailyMetricsRepository
    extends JpaRepository<MoneyDailyMetrics, MoneyDailyMetrics.Pk> {

  Optional<MoneyDailyMetrics> findByStockCodeAndTradeDate(String stockCode, LocalDate tradeDate);

  List<MoneyDailyMetrics> findByStockCodeInAndTradeDate(
      Collection<String> stockCodes, LocalDate tradeDate);

  Optional<MoneyDailyMetrics> findFirstByStockCodeOrderByTradeDateDesc(String stockCode);
}
