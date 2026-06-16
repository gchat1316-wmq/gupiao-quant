package com.quant.service;

import com.quant.entity.InvestStockPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
public class AStockDataForecastProvider implements InvestForecastProvider {

    @Override
    public Optional<RevenueForecast> fetchRevenueForecast(InvestStockPool pool) {
        log.debug("a-stock-data revenue forecast provider has no configured direct revenue endpoint for {}", pool.getStockCode());
        return Optional.empty();
    }
}
