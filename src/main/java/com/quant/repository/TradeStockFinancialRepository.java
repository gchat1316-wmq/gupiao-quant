package com.quant.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.quant.entity.TradeStockFinancial;

public interface TradeStockFinancialRepository extends JpaRepository<TradeStockFinancial, Integer> {

  // 同时匹配裸代码（600519）和带交易所后缀（600519.SH / 600519.SZ）
  @Query(
      "SELECT f FROM TradeStockFinancial f WHERE f.stockCode = :code OR f.stockCode LIKE CONCAT(:code, '.%') ORDER BY f.reportDate DESC")
  List<TradeStockFinancial> findByStockCodeOrderByReportDateDesc(@Param("code") String stockCode);

  // 按 report_date 区间取，用于回填历史营收/年报等
  @Query(
      "SELECT f FROM TradeStockFinancial f WHERE f.stockCode = :code AND f.reportDate BETWEEN :from AND :to ORDER BY f.reportDate DESC")
  List<TradeStockFinancial> findByStockCodeAndReportDateBetween(
      @Param("code") String stockCode, @Param("from") LocalDate from, @Param("to") LocalDate to);

  // 按股票名称模糊匹配，用于在 trade_stock_basic 中找不到时的兜底
  // 兼容 BaoStock 在除权除息期间返回的简称（如 "XD兆易创"），允许用户输入全名 "兆易创新" 命中。
  @Query(
      "SELECT f FROM TradeStockFinancial f WHERE "
          + "f.stockName = :name OR "
          + "f.stockName LIKE CONCAT('%', :name, '%') OR "
          + "(LENGTH(:name) >= 2 AND f.stockName LIKE CONCAT('XD', SUBSTRING(:name, 1, LENGTH(:name) - 1), '%')) OR "
          + "(LENGTH(:name) >= 2 AND f.stockName LIKE CONCAT('XR', SUBSTRING(:name, 1, LENGTH(:name) - 1), '%')) OR "
          + "(LENGTH(:name) >= 2 AND f.stockName LIKE CONCAT('DR', SUBSTRING(:name, 1, LENGTH(:name) - 1), '%')) "
          + "ORDER BY f.reportDate DESC")
  List<TradeStockFinancial> findByStockNameLike(@Param("name") String name);

  // 批量查各股票最新一条财务记录，供 listPool() 批量加载用（消除 N+1）
  @Query(
      "SELECT f FROM TradeStockFinancial f WHERE f.stockCode IN :codes "
          + "AND f.reportDate = (SELECT MAX(f2.reportDate) FROM TradeStockFinancial f2 WHERE f2.stockCode = f.stockCode)")
  List<TradeStockFinancial> findLatestByStockCodes(@Param("codes") Collection<String> codes);
}
