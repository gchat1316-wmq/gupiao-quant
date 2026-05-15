package com.quant.repository;

import com.quant.entity.TradeStockFinancial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TradeStockFinancialRepository extends JpaRepository<TradeStockFinancial, Integer> {

    @Query("SELECT f FROM TradeStockFinancial f WHERE f.stockCode = :stockCode ORDER BY f.reportDate DESC")
    List<TradeStockFinancial> findByStockCodeOrderByReportDateDesc(@Param("stockCode") String stockCode);
}
