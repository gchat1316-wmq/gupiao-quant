package com.quant.repository;

import com.quant.entity.XieboWeeklyOpportunitySlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface XieboWeeklyOpportunitySlotRepository
        extends JpaRepository<XieboWeeklyOpportunitySlot, Long> {

    List<XieboWeeklyOpportunitySlot> findByPoolTypeOrderBySlotIndexAsc(String poolType);

    @Transactional
    void deleteByPoolType(String poolType);
}
