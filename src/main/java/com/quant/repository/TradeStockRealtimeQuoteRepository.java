package com.quant.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.TradeStockRealtimeQuote;

public interface TradeStockRealtimeQuoteRepository
    extends JpaRepository<TradeStockRealtimeQuote, String> {

  List<TradeStockRealtimeQuote> findByStockCodeIn(Collection<String> stockCodes);
}
