package com.quant.repository;

import com.quant.entity.JournalTrade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JournalTradeRepository extends JpaRepository<JournalTrade, Long>, JpaSpecificationExecutor<JournalTrade> {

    /** Soft-deletion aware base query — never returns is_deleted=1 */
    @Query("SELECT j FROM JournalTrade j WHERE j.isDeleted = 0")
    Page<JournalTrade> findAllActive(Pageable pageable);

    @Query("SELECT j FROM JournalTrade j WHERE j.id = :id AND j.isDeleted = 0")
    Optional<JournalTrade> findActiveById(@Param("id") Long id);

    @Query("SELECT j FROM JournalTrade j WHERE j.isDeleted = 0 AND j.isOpen = 1 ORDER BY j.entryDate DESC")
    List<JournalTrade> findAllOpen();

    @Query("SELECT j FROM JournalTrade j WHERE j.isDeleted = 0 AND j.isOpen = 0 ORDER BY j.exitDate DESC")
    List<JournalTrade> findAllClosed();

    @Query("SELECT j FROM JournalTrade j WHERE j.isDeleted = 0 AND j.mode = :mode AND j.isOpen = 0 ORDER BY j.exitDate ASC")
    List<JournalTrade> findClosedByMode(@Param("mode") JournalTrade.Mode mode);

    @Query("SELECT j FROM JournalTrade j WHERE j.isDeleted = 0 AND j.isOpen = 0 ORDER BY j.exitDate ASC")
    List<JournalTrade> findAllClosedOrdered();

    @Query("SELECT j FROM JournalTrade j WHERE j.source = 'POOL_SYNC' AND j.sourceRefId = :refId")
    Optional<JournalTrade> findBySourceRef(@Param("refId") Long refId);
}