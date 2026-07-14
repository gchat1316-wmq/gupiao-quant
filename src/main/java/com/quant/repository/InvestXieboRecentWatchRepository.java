package com.quant.repository;

import com.quant.entity.InvestXieboRecentWatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvestXieboRecentWatchRepository
        extends JpaRepository<InvestXieboRecentWatch, String> {
    List<InvestXieboRecentWatch> findAllByOrderByCreatedAtDesc();
    List<InvestXieboRecentWatch> findByTypeOrderByCreatedAtDesc(String type);
}
