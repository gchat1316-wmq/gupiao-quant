package com.quant.repository;

import com.quant.entity.TradeStockRealtimeQuote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TradeStockRealtimeQuoteRepository extends JpaRepository<TradeStockRealtimeQuote, String> {

    List<TradeStockRealtimeQuote> findByStockCodeIn(Collection<String> stockCodes);
}
