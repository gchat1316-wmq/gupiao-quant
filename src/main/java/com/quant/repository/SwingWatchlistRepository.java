package com.quant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.SwingWatchlist;

public interface SwingWatchlistRepository extends JpaRepository<SwingWatchlist, Long> {

  List<SwingWatchlist> findByUserIdOrderByUpdatedAtDesc(Long userId);

  Optional<SwingWatchlist> findByIdAndUserId(Long id, Long userId);

  Optional<SwingWatchlist> findByUserIdAndStockCode(Long userId, String stockCode);

  List<SwingWatchlist> findByStatusIn(Collection<String> statuses);

  long countByUserIdAndStatusIn(Long userId, Collection<String> statuses);
}
