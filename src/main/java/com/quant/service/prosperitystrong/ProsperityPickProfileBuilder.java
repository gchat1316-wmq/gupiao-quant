package com.quant.service.prosperitystrong;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.quant.dto.invest.ProsperityPickResultDTO;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.StockQueryService;
import com.quant.service.aistockdata.AStockDataQuoteService;

import lombok.RequiredArgsConstructor;

/**
 * Builds the {@link ProsperityPickResultDTO.Profile} block for a single stock — pulls live quote
 * from {@link AStockDataQuoteService} (preferred over the daily-snapshot close) and falls back to
 * DB financial records for the latest report date, revenue, net profit.
 */
@Component
@RequiredArgsConstructor
public class ProsperityPickProfileBuilder {

  private final StockQueryService stockQueryService;
  private final TradeStockFinancialRepository financialRepo;
  private final AStockDataQuoteService aStockDataQuoteService;

  public ProsperityPickResultDTO.Profile buildProfile(TradeStockBasic basic) {
    ProsperityPickResultDTO.Profile.ProfileBuilder pb =
        ProsperityPickResultDTO.Profile.builder()
            .stockCode(basic.getStockCode())
            .stockName(basic.getStockName())
            .exchange(basic.getExchange())
            .board(StockQueryService.deriveBoard(basic.getStockCode()))
            .industry(basic.getSectorNames())
            .peTtm(basic.getPeTtm())
            .pb(basic.getPb())
            .psTtm(basic.getPsTtm());

    // 当前价/市值统一走 a-stock-data 实时接口；trade_stock_daily 收盘价同步延迟、不准确
    Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap =
        aStockDataQuoteService.fetchQuotes(List.of(basic.getStockCode()));
    AStockDataQuoteService.QuoteSnapshot snapshot =
        quoteMap == null
            ? null
            : quoteMap.get(
                basic.getStockCode() == null
                    ? ""
                    : basic.getStockCode().trim().toUpperCase(Locale.ROOT));
    if (snapshot != null && snapshot.latestPrice() != null) {
      pb.currentPrice(snapshot.latestPrice());
      if (snapshot.totalMarketCapYi() != null
          && snapshot.totalMarketCapYi().compareTo(BigDecimal.ZERO) > 0) {
        pb.totalMarketCap(snapshot.totalMarketCapYi());
      } else if (basic.getTotalShares() != null) {
        BigDecimal cap =
            snapshot
                .latestPrice()
                .multiply(BigDecimal.valueOf(basic.getTotalShares()))
                .divide(BigDecimal.valueOf(100_000_000L), 2, RoundingMode.HALF_UP);
        pb.totalMarketCap(cap);
      }
    }

    List<TradeStockFinancial> fin =
        financialRepo.findByStockCodeOrderByReportDateDesc(basic.getStockCode());
    if (!fin.isEmpty()) {
      TradeStockFinancial latest = fin.get(0);
      pb.latestReportDate(
          latest.getReportDate() != null ? latest.getReportDate().toString() : null);
      pb.latestRevenue(formatYi(latest.getRevenue()));
      pb.latestNetProfit(formatYi(latest.getNetProfit()));
    }
    return pb.build();
  }

  private String formatYi(BigDecimal raw) {
    if (raw == null) return null;
    BigDecimal yi = raw.divide(BigDecimal.valueOf(100_000_000L), 2, RoundingMode.HALF_UP);
    return yi + " 亿";
  }
}
