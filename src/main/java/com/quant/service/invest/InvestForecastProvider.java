package com.quant.service.invest;

import java.math.BigDecimal;
import java.util.Optional;

import com.quant.entity.InvestStockPool;

public interface InvestForecastProvider {

  Optional<RevenueForecast> fetchRevenueForecast(InvestStockPool pool);

  record RevenueForecast(
      BigDecimal revenueForecastY0, BigDecimal revenueForecastY1, BigDecimal revenueForecastY2) {}
}
