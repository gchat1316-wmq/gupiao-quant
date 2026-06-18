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

    /**
     * 按名称模糊匹配。
     * 兼容 BaoStock 在除权除息期间返回的简称（如 "XD兆易创"），允许用户输入全名 "兆易创新" 命中。
     */
    @Query("SELECT s FROM TradeStockBasic s WHERE " +
           "s.stockName = :name OR " +
           "s.stockName LIKE CONCAT('%', :name, '%') OR " +
           "(LENGTH(:name) >= 2 AND s.stockName LIKE CONCAT('XD', SUBSTRING(:name, 1, LENGTH(:name) - 1), '%')) OR " +
           "(LENGTH(:name) >= 2 AND s.stockName LIKE CONCAT('XR', SUBSTRING(:name, 1, LENGTH(:name) - 1), '%')) OR " +
           "(LENGTH(:name) >= 2 AND s.stockName LIKE CONCAT('DR', SUBSTRING(:name, 1, LENGTH(:name) - 1), '%'))")
    List<TradeStockBasic> findByStockNameLike(@Param("name") String name);

    /** 按 sector_names 字段模糊匹配板块名称(成分股查询) */
    @Query("SELECT s FROM TradeStockBasic s WHERE s.sectorNames LIKE CONCAT('%', :sectorName, '%')")
    List<TradeStockBasic> findBySectorNameLike(@Param("sectorName") String sectorName);
}
