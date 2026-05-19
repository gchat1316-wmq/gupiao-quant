package com.quant.repository;

import com.quant.entity.InvestQuote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface InvestQuoteRepository extends JpaRepository<InvestQuote, Long> {

    @Query("SELECT q FROM InvestQuote q WHERE " +
           "(:kw IS NULL OR :kw = '' OR " +
           " q.content LIKE %:kw% OR q.tags LIKE %:kw% OR q.author LIKE %:kw%) " +
           "ORDER BY q.id DESC")
    Page<InvestQuote> search(@Param("kw") String kw, Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE InvestQuote q SET q.likes = q.likes + 1 WHERE q.id = :id")
    void incrementLikes(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("UPDATE InvestQuote q SET q.importedNodeId = :nodeId WHERE q.id = :id")
    void setImportedNodeId(@Param("id") Long id, @Param("nodeId") Long nodeId);
}
