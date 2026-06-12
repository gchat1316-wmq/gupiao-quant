package com.quant.repository;

import com.quant.entity.StockAnalysisRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockAnalysisRecordRepository extends JpaRepository<StockAnalysisRecord, Long> {

    /** 列表: 按股票代码或名称模糊搜索 + 状态过滤 */
    @Query("SELECT r FROM StockAnalysisRecord r WHERE " +
           "(:kw IS NULL OR :kw = '' OR " +
           " r.stockCode LIKE %:kw% OR r.stockCodeRaw LIKE %:kw% OR r.stockName LIKE %:kw%) " +
           "AND (:status IS NULL OR :status = '' OR r.status = :status) " +
           "ORDER BY r.id DESC")
    Page<StockAnalysisRecord> search(@Param("kw") String kw,
                                    @Param("status") String status,
                                    Pageable pageable);

    /** 最近 1 条成功的同代码记录 (用于缓存命中) */
    @Query("SELECT r FROM StockAnalysisRecord r WHERE r.stockCode = :code " +
           "AND r.status = 'SUCCESS' AND r.method = :method " +
           "ORDER BY r.id DESC")
    Page<StockAnalysisRecord> findLatestSuccess(@Param("code") String code,
                                                @Param("method") String method,
                                                Pageable pageable);
}
