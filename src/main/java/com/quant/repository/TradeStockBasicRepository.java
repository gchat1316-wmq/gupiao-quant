package com.quant.repository;

import com.quant.entity.TradeStockBasic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TradeStockBasicRepository extends JpaRepository<TradeStockBasic, String> {

    Optional<TradeStockBasic> findByStockCode(String stockCode);

    /** 支持裸代码匹配，如 "600519" 命中 "600519.SH" */
    @Query("SELECT s FROM TradeStockBasic s WHERE s.stockCode LIKE CONCAT(:prefix, '.%')")
    List<TradeStockBasic> findByStockCodePrefix(@Param("prefix") String prefix);

    List<TradeStockBasic> findByStockCodeIn(Collection<String> codes);

    @Query("SELECT s FROM TradeStockBasic s WHERE s.stockName = :name OR s.stockName LIKE CONCAT('%', :name, '%')")
    List<TradeStockBasic> findByStockNameLike(@Param("name") String name);
}
