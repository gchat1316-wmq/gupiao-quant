package com.quant.repository;

import com.quant.entity.TradeStockRealtimeKline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TradeStockRealtimeKlineRepository extends JpaRepository<TradeStockRealtimeKline, Long> {

    @Query(value = """
            SELECT k.* FROM trade_stock_realtime_kline k
            INNER JOIN (
              SELECT stock_code, MAX(kline_time) AS max_time
              FROM trade_stock_realtime_kline
              WHERE stock_code IN (:codes) AND period = :period
              GROUP BY stock_code
            ) latest
            ON k.stock_code = latest.stock_code AND k.kline_time = latest.max_time
            WHERE k.period = :period
            """, nativeQuery = true)
    List<TradeStockRealtimeKline> findLatestByStockCodesAndPeriod(
            @Param("codes") Collection<String> codes,
            @Param("period") String period);
}
