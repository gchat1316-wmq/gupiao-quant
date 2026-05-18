package com.quant.repository;

import com.quant.entity.TradeStockFinancial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TradeStockFinancialRepository extends JpaRepository<TradeStockFinancial, Integer> {

    // 同时匹配裸代码（600519）和带交易所后缀（600519.SH / 600519.SZ）
    @Query("SELECT f FROM TradeStockFinancial f WHERE f.stockCode = :code OR f.stockCode LIKE CONCAT(:code, '.%') ORDER BY f.reportDate DESC")
    List<TradeStockFinancial> findByStockCodeOrderByReportDateDesc(@Param("code") String stockCode);
}
