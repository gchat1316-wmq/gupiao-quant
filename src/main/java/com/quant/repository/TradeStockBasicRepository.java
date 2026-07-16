package com.quant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.quant.entity.TradeStockBasic;

public interface TradeStockBasicRepository extends JpaRepository<TradeStockBasic, String> {

  Optional<TradeStockBasic> findByStockCode(String stockCode);

  /** 支持裸代码匹配，如 "600519" 命中 "600519.SH" */
  @Query("SELECT s FROM TradeStockBasic s WHERE s.stockCode LIKE CONCAT(:prefix, '.%')")
  List<TradeStockBasic> findByStockCodePrefix(@Param("prefix") String prefix);

  List<TradeStockBasic> findByStockCodeIn(Collection<String> codes);

  /** 按名称模糊匹配。 兼容 BaoStock 在除权除息期间返回的简称（如 "XD兆易创"），允许用户输入全名 "兆易创新" 命中。 */
  @Query(
      "SELECT s FROM TradeStockBasic s WHERE "
          + "s.stockName = :name OR "
          + "s.stockName LIKE CONCAT('%', :name, '%') OR "
          + "(LENGTH(:name) >= 2 AND s.stockName LIKE CONCAT('XD', SUBSTRING(:name, 1, LENGTH(:name) - 1), '%')) OR "
          + "(LENGTH(:name) >= 2 AND s.stockName LIKE CONCAT('XR', SUBSTRING(:name, 1, LENGTH(:name) - 1), '%')) OR "
          + "(LENGTH(:name) >= 2 AND s.stockName LIKE CONCAT('DR', SUBSTRING(:name, 1, LENGTH(:name) - 1), '%'))")
  List<TradeStockBasic> findByStockNameLike(@Param("name") String name);

  /** 按 sector_names 字段模糊匹配板块名称(成分股查询) */
  @Query("SELECT s FROM TradeStockBasic s WHERE s.sectorNames LIKE CONCAT('%', :sectorName, '%')")
  List<TradeStockBasic> findBySectorNameLike(@Param("sectorName") String sectorName);

  /** 预加载全表 sector_names / stock_code 到内存做倒排索引。 仅查询必要字段，避免全量 ORM 映射开销。 */
  @Query(
      value =
          "SELECT stock_code, sector_names FROM trade_stock_basic WHERE sector_names IS NOT NULL AND sector_names != ''",
      nativeQuery = true)
  List<Object[]> findAllSectorNamesRaw();
}
