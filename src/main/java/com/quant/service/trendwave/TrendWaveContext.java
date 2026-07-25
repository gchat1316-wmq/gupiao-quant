package com.quant.service.trendwave;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.quant.entity.MoneyPosition;
import com.quant.entity.MoneySetup;
import com.quant.entity.MoneyStockPool;
import com.quant.entity.MoneyWatch;
import com.quant.entity.TradeStockDaily;
import com.quant.service.technical.MovingAverageCalculator.MovingAverages;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TrendWaveContext {
  private MoneyStockPool pool;
  private MoneyWatch watch;
  private List<MoneySetup> setups;
  private MoneyPosition position;
  private List<TradeStockDaily> dailyAsc;
  private MovingAverages mas;
  private BigDecimal latestPrice;
  private BigDecimal todayOpen;
  private BigDecimal todayHigh;
  private BigDecimal todayLow;
  private Long todayVolume;
  private boolean eodScan;
  private boolean indexAboveMa20;
  private String marketRegime;
  private LocalDateTime now;
}
