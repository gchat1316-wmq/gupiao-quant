package com.quant.repository;

import com.quant.entity.InvestXieboWatchlist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvestXieboWatchlistRepository extends JpaRepository<InvestXieboWatchlist, Long> {

    List<InvestXieboWatchlist> findAllByOrderByDisplayOrderAscCreatedAtAsc();

    Optional<InvestXieboWatchlist> findByStockCode(String stockCode);

    void deleteByStockCode(String stockCode);
}
