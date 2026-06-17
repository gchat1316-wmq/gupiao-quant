package com.quant.repository;

import com.quant.entity.InvestLynchWatchlist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvestLynchWatchlistRepository extends JpaRepository<InvestLynchWatchlist, Long> {

    List<InvestLynchWatchlist> findAllByOrderByDisplayOrderAscCreatedAtAsc();

    Optional<InvestLynchWatchlist> findByStockCode(String stockCode);

    void deleteByStockCode(String stockCode);
}
