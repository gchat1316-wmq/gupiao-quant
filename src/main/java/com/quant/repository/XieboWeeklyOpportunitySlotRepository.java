package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.quant.entity.XieboWeeklyOpportunitySlot;

public interface XieboWeeklyOpportunitySlotRepository
    extends JpaRepository<XieboWeeklyOpportunitySlot, Long> {

  List<XieboWeeklyOpportunitySlot> findByPoolTypeOrderBySlotIndexAsc(String poolType);

  @Transactional
  void deleteByPoolType(String poolType);
}
