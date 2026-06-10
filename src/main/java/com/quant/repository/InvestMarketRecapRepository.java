package com.quant.repository;

import com.quant.entity.InvestMarketRecap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InvestMarketRecapRepository extends JpaRepository<InvestMarketRecap, Long> {

    @Query("select distinct r.market from InvestMarketRecap r")
    List<String> findDistinctMarkets();

    List<InvestMarketRecap> findByMarketOrderByTradeDateDescIdDesc(String market);
}
