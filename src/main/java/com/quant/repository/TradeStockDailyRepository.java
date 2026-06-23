package com.quant.repository;

import com.quant.entity.TradeStockDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TradeStockDailyRepository extends JpaRepository<TradeStockDaily, Integer> {

    /** 取指定股票的最新一条 daily 记录 */
    Optional<TradeStockDaily> findFirstByStockCodeOrderByTradeDateDesc(String stockCode);

    List<TradeStockDaily> findTop6ByStockCodeOrderByTradeDateDesc(String stockCode);

    /** 取最近 N 日 daily（用于 ATR 计算），按日期倒序 */
    List<TradeStockDaily> findTop30ByStockCodeOrderByTradeDateDesc(String stockCode);

    List<TradeStockDaily> findByStockCodeAndTradeDateGreaterThanOrderByTradeDateAsc(String stockCode, LocalDate tradeDate);

    /** 取指定股票在日期区间内的全部 daily（实战选股：月线分析用） */
    List<TradeStockDaily> findByStockCodeAndTradeDateBetweenOrderByTradeDateAsc(
            String stockCode, LocalDate fromDate, LocalDate toDate);

    /** 取指定股票在指定日期及之后的首条 daily 记录（用于年初涨幅基准价） */
    Optional<TradeStockDaily> findFirstByStockCodeAndTradeDateGreaterThanEqualOrderByTradeDateAsc(
            String stockCode, LocalDate fromDate);

    /** 批量取多只股票的最新 daily 记录 */
    @Query(value = """
            SELECT d.* FROM trade_stock_daily d
            INNER JOIN (
              SELECT stock_code, MAX(trade_date) AS max_date
              FROM trade_stock_daily
              WHERE stock_code IN (:codes)
              GROUP BY stock_code
            ) latest
            ON d.stock_code = latest.stock_code AND d.trade_date = latest.max_date
            """, nativeQuery = true)
    List<TradeStockDaily> findLatestByStockCodes(@Param("codes") Collection<String> codes);

    /** 批量取每只股票在指定日期及之后的首条 daily 记录（年初基准价） */
    @Query(value = """
            SELECT d.* FROM trade_stock_daily d
            INNER JOIN (
              SELECT stock_code, MIN(trade_date) AS min_date
              FROM trade_stock_daily
              WHERE stock_code IN (:codes) AND trade_date >= :fromDate
              GROUP BY stock_code
            ) base
            ON d.stock_code = base.stock_code AND d.trade_date = base.min_date
            """, nativeQuery = true)
    List<TradeStockDaily> findFirstAfterDateByStockCodes(
            @Param("codes") Collection<String> codes,
            @Param("fromDate") LocalDate fromDate);
}
