package com.quant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.InvestXieboWatchlist;

public interface InvestXieboWatchlistRepository extends JpaRepository<InvestXieboWatchlist, Long> {

  List<InvestXieboWatchlist> findAllByOrderByDisplayOrderAscCreatedAtAsc();

  Optional<InvestXieboWatchlist> findByStockCode(String stockCode);

  void deleteByStockCode(String stockCode);
}
