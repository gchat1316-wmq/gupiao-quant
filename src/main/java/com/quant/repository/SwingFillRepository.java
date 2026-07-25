package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.SwingFill;

public interface SwingFillRepository extends JpaRepository<SwingFill, Long> {

  List<SwingFill> findByWatchIdOrderByFillTimeDesc(Long watchId);

  List<SwingFill> findByPositionIdOrderByFillTimeAsc(Long positionId);
}
