package com.quant.repository;

import com.quant.entity.InvestWeeklyOpportunitySlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InvestWeeklyOpportunitySlotRepository
        extends JpaRepository<InvestWeeklyOpportunitySlot, Long> {

    List<InvestWeeklyOpportunitySlot> findByPoolTypeOrderBySlotIndexAsc(String poolType);

    @Modifying
    @Query("DELETE FROM InvestWeeklyOpportunitySlot s WHERE s.poolType = :poolType")
    int deleteByPoolType(@Param("poolType") String poolType);
}
