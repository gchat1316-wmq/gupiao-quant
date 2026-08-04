package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.EtfTrade;

public interface EtfTradeRepository extends JpaRepository<EtfTrade, Long> {

  List<EtfTrade> findByPoolIdOrderByTradeTimeAscIdAsc(Long poolId);

  List<EtfTrade> findAllByOrderByTradeTimeDescIdDesc();

  long countByPoolIdAndDirectionAndTradeTypeIn(
      Long poolId, String direction, List<String> tradeTypes);
}
