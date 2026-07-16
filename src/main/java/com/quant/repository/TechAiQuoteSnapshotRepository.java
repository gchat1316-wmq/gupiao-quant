package com.quant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.quant.entity.TechAiQuoteSnapshot;

public interface TechAiQuoteSnapshotRepository extends JpaRepository<TechAiQuoteSnapshot, Long> {

  Optional<TechAiQuoteSnapshot> findFirstByStockCodeOrderByQuoteTimeDesc(String stockCode);

  @Query(
      value =
          """
            SELECT q.* FROM tech_ai_quote_snapshot q
            INNER JOIN (
              SELECT stock_code, MAX(quote_time) AS max_time
              FROM tech_ai_quote_snapshot
              WHERE stock_code IN (:codes)
              GROUP BY stock_code
            ) latest
            ON q.stock_code = latest.stock_code AND q.quote_time = latest.max_time
            """,
      nativeQuery = true)
  List<TechAiQuoteSnapshot> findLatestByStockCodes(@Param("codes") Collection<String> codes);
}
