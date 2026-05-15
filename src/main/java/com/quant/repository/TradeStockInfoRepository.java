package com.quant.repository;

import com.quant.entity.TradeStockInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TradeStockInfoRepository extends JpaRepository<TradeStockInfo, Integer> {

    Optional<TradeStockInfo> findByStockCode(String stockCode);

    @Query("SELECT s FROM TradeStockInfo s WHERE s.stockName = :name OR s.stockName LIKE CONCAT('%', :name, '%')")
    List<TradeStockInfo> findByStockNameLike(@Param("name") String name);
}
