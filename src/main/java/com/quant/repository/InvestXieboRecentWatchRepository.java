package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.quant.entity.InvestXieboRecentWatch;

@Repository
public interface InvestXieboRecentWatchRepository
    extends JpaRepository<InvestXieboRecentWatch, String> {
  List<InvestXieboRecentWatch> findAllByOrderByCreatedAtDesc();

  List<InvestXieboRecentWatch> findByTypeOrderByCreatedAtDesc(String type);
}
