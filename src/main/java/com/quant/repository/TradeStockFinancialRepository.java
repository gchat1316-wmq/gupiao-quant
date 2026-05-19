package com.quant.repository;

import com.quant.entity.TradeStockFinancial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TradeStockFinancialRepository extends JpaRepository<TradeStockFinancial, Integer> {

    // 同时匹配裸代码（600519）和带交易所后缀（600519.SH / 600519.SZ）
    @Query("SELECT f FROM TradeStockFinancial f WHERE f.stockCode = :code OR f.stockCode LIKE CONCAT(:code, '.%') ORDER BY f.reportDate DESC")
    List<TradeStockFinancial> findByStockCodeOrderByReportDateDesc(@Param("code") String stockCode);

    // 按股票名称模糊匹配，用于在 trade_stock_info 中找不到时的兜底
    @Query("SELECT f FROM TradeStockFinancial f WHERE f.stockName = :name OR f.stockName LIKE CONCAT('%', :name, '%') ORDER BY f.reportDate DESC")
    List<TradeStockFinancial> findByStockNameLike(@Param("name") String name);

    // 批量查各股票最新一条财务记录，供 listPool() 批量加载用（消除 N+1）
    @Query("SELECT f FROM TradeStockFinancial f WHERE f.stockCode IN :codes " +
           "AND f.reportDate = (SELECT MAX(f2.reportDate) FROM TradeStockFinancial f2 WHERE f2.stockCode = f.stockCode)")
    List<TradeStockFinancial> findLatestByStockCodes(@Param("codes") Collection<String> codes);
}
