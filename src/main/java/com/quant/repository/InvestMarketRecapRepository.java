package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.quant.entity.InvestMarketRecap;

public interface InvestMarketRecapRepository extends JpaRepository<InvestMarketRecap, Long> {

  @Query("select distinct r.market from InvestMarketRecap r")
  List<String> findDistinctMarkets();

  List<InvestMarketRecap> findByMarketOrderByTradeDateDescIdDesc(String market);

  List<InvestMarketRecap> findAllByOrderByTradeDateDescIdDesc();
}
