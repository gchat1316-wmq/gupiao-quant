package com.quant.service.xieboinvest;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.dto.xieboinvest.XieboQuoteDTO;
import com.quant.dto.xieboinvest.XieboWatchlistItemDTO;
import com.quant.entity.InvestXieboWatchlist;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.InvestXieboWatchlistRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.AStockDataQuoteService;
import com.quant.service.Ps10ValuationService;
import com.quant.service.StockQueryService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class XieboInvestService {

  private static final BigDecimal YI = BigDecimal.valueOf(100_000_000L);

  private final InvestXieboWatchlistRepository watchlistRepository;
  private final TradeStockBasicRepository stockBasicRepository;
  private final AStockDataQuoteService aStockDataQuoteService;
  private final TradeStockFinancialRepository financialRepository;
  private final StockQueryService stockQueryService;
  private final Ps10ValuationService ps10ValuationService;

  public List<XieboWatchlistItemDTO> getWatchlist() {
    List<InvestXieboWatchlist> rows =
        watchlistRepository.findAllByOrderByDisplayOrderAscCreatedAtAsc();
    if (rows.isEmpty()) {
      return List.of();
    }
    List<String> codes = rows.stream().map(InvestXieboWatchlist::getStockCode).toList();
    Map<String, TradeStockBasic> basicMap =
        stockBasicRepository.findByStockCodeIn(codes).stream()
            .collect(Collectors.toMap(TradeStockBasic::getStockCode, Function.identity()));
    // 当前价统一走 a-stock-data 实时接口；trade_stock_daily 收盘价同步延迟、不准确
    Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap =
        aStockDataQuoteService.fetchQuotes(codes);

    return rows.stream()
        .map(
            row ->
                toWatchlistItem(
                    row,
                    basicMap.get(row.getStockCode()),
                    quoteMap.get(normalizeKey(row.getStockCode()))))
        .toList();
  }

  public XieboQuoteDTO getQuote(String keyword) {
    TradeStockBasic basic =
        stockQueryService
            .resolveStock(keyword)
            .orElseThrow(() -> new IllegalArgumentException("未找到股票: " + keyword));
    // 当前价统一走 a-stock-data 实时接口
    Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap =
        aStockDataQuoteService.fetchQuotes(List.of(basic.getStockCode()));
    return toQuote(basic, quoteMap.get(normalizeKey(basic.getStockCode())));
  }

  @Transactional
  public List<XieboWatchlistItemDTO> addWatchlist(String keyword) {
    TradeStockBasic basic =
        stockQueryService
            .resolveStock(keyword)
            .orElseThrow(() -> new IllegalArgumentException("未找到股票: " + keyword));
    if (watchlistRepository.findByStockCode(basic.getStockCode()).isEmpty()) {
      InvestXieboWatchlist row = new InvestXieboWatchlist();
      row.setStockCode(basic.getStockCode());
      row.setStockName(basic.getStockName());
      row.setCreatedAt(LocalDateTime.now());
      watchlistRepository.save(row);
    }
    return getWatchlist();
  }

  @Transactional
  public void removeWatchlist(String stockCode) {
    watchlistRepository.deleteByStockCode(stockCode);
  }

  public Map<String, Object> getSectorPe(String keyword) {
    TradeStockBasic basic =
        stockQueryService
            .resolveStock(keyword)
            .orElseThrow(() -> new IllegalArgumentException("未找到股票: " + keyword));
    String sectorName = firstSector(basic.getSectorNames());
    List<TradeStockBasic> peers =
        stockBasicRepository.findBySectorNameLike(sectorName).stream()
            .filter(
                item -> item.getPeTtm() != null && item.getPeTtm().compareTo(BigDecimal.ZERO) > 0)
            .toList();
    List<String> codes = peers.stream().map(TradeStockBasic::getStockCode).toList();
    // 当前价统一走 a-stock-data 实时接口
    Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap =
        aStockDataQuoteService.fetchQuotes(codes);

    List<Map<String, Object>> stocks = new ArrayList<>();
    for (TradeStockBasic peer : peers) {
      AStockDataQuoteService.QuoteSnapshot snapshot =
          quoteMap.get(normalizeKey(peer.getStockCode()));
      BigDecimal price = snapshot == null ? null : snapshot.latestPrice();
      BigDecimal marketCap = computeMarketCap(peer, price);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("stockCode", peer.getStockCode());
      row.put("stockName", peer.getStockName());
      row.put("price", price);
      row.put("peTtm", peer.getPeTtm().setScale(2, RoundingMode.HALF_UP));
      row.put("pb", peer.getPb());
      row.put("marketCap", marketCap);
      stocks.add(row);
    }
    stocks.sort(
        Comparator.comparing(
            row -> Optional.ofNullable((BigDecimal) row.get("marketCap")).orElse(BigDecimal.ZERO),
            Comparator.reverseOrder()));

    List<BigDecimal> peValues =
        peers.stream()
            .map(TradeStockBasic::getPeTtm)
            .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0)
            .sorted()
            .toList();
    BigDecimal avgPe =
        peValues.isEmpty()
            ? BigDecimal.ZERO
            : peValues.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(peValues.size()), 2, RoundingMode.HALF_UP);
    BigDecimal medianPe = median(peValues);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sectorName", sectorName);
    result.put("stocks", stocks.stream().limit(20).toList());
    result.put("avgPe", avgPe);
    result.put("medianPe", medianPe);
    result.put("count", stocks.size());
    return result;
  }

  private XieboWatchlistItemDTO toWatchlistItem(
      InvestXieboWatchlist row,
      TradeStockBasic basic,
      AStockDataQuoteService.QuoteSnapshot snapshot) {
    TradeStockBasic actualBasic = basic != null ? basic : fallbackBasic(row);
    QuoteMetrics metrics = buildMetrics(actualBasic, snapshot);
    return XieboWatchlistItemDTO.builder()
        .stockCode(row.getStockCode())
        .stockName(row.getStockName())
        .sectorName(firstSector(actualBasic.getSectorNames()))
        .price(metrics.price())
        .peTtm(actualBasic.getPeTtm())
        .pb(actualBasic.getPb())
        .marketCap(metrics.marketCap())
        .cagrPct(metrics.cagrPct())
        .peg(metrics.peg())
        .pegRating(metrics.pegRating())
        .digestYears(metrics.digestYears())
        .valuationVerdict(metrics.valuationVerdict())
        .valuationCommentary(metrics.valuationCommentary())
        .valuationDeviationPct(metrics.valuationDeviationPct())
        .valuationDeviationRef(metrics.valuationDeviationRef())
        .valuationDeviationLabel(metrics.valuationDeviationLabel())
        .build();
  }

  private XieboQuoteDTO toQuote(
      TradeStockBasic basic, AStockDataQuoteService.QuoteSnapshot snapshot) {
    QuoteMetrics metrics = buildMetrics(basic, snapshot);
    return XieboQuoteDTO.builder()
        .stockCode(basic.getStockCode())
        .stockName(basic.getStockName())
        .sectorName(firstSector(basic.getSectorNames()))
        .price(metrics.price())
        .peTtm(basic.getPeTtm())
        .pb(basic.getPb())
        .marketCap(metrics.marketCap())
        .cagrPct(metrics.cagrPct())
        .peg(metrics.peg())
        .pegRating(metrics.pegRating())
        .digestYears(metrics.digestYears())
        .valuationVerdict(metrics.valuationVerdict())
        .valuationCommentary(metrics.valuationCommentary())
        .build();
  }

  private QuoteMetrics buildMetrics(
      TradeStockBasic basic, AStockDataQuoteService.QuoteSnapshot snapshot) {
    BigDecimal price = snapshot == null ? null : snapshot.latestPrice();
    BigDecimal marketCap = computeMarketCap(basic, price);
    BigDecimal cagrPct =
        computeCagrPct(
            financialRepository.findByStockCodeOrderByReportDateDesc(basic.getStockCode()));
    BigDecimal peg = computePeg(basic.getPeTtm(), cagrPct);
    String pegRating = ratePeg(peg);
    BigDecimal digestYears = computeDigestYears(basic.getPeTtm(), cagrPct);
    // 10xPS 统一估值
    var financials = financialRepository.findByStockCodeOrderByReportDateDesc(basic.getStockCode());
    Ps10ValuationService.Ps10Result ps10 =
        ps10ValuationService.evaluateFromMarketCap(
            marketCap, price, basic.getStockCode(), financials);
    return new QuoteMetrics(
        price,
        marketCap,
        cagrPct,
        peg,
        pegRating,
        digestYears,
        ps10.verdict(),
        ps10.commentary(),
        ps10.deviationPct(),
        ps10.deviationRef(),
        ps10.deviationLabel());
  }

  private String normalizeKey(String code) {
    return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
  }

  private TradeStockBasic fallbackBasic(InvestXieboWatchlist row) {
    TradeStockBasic basic = new TradeStockBasic();
    basic.setStockCode(row.getStockCode());
    basic.setStockName(row.getStockName());
    return basic;
  }

  private BigDecimal computeMarketCap(TradeStockBasic basic, BigDecimal price) {
    if (basic == null || basic.getTotalShares() == null || price == null) {
      return null;
    }
    return price
        .multiply(BigDecimal.valueOf(basic.getTotalShares()))
        .divide(YI, 2, RoundingMode.HALF_UP);
  }

  private BigDecimal computeCagrPct(Collection<TradeStockFinancial> records) {
    List<TradeStockFinancial> annual =
        records.stream()
            .filter(
                f ->
                    f.getReportDate() != null
                        && f.getReportDate().getMonthValue() == 12
                        && f.getReportDate().getDayOfMonth() == 31
                        && f.getNetProfit() != null
                        && f.getNetProfit().compareTo(BigDecimal.ZERO) > 0)
            .limit(4)
            .toList();
    if (annual.size() < 2) {
      return null;
    }
    TradeStockFinancial latest = annual.get(0);
    TradeStockFinancial oldest = annual.get(annual.size() - 1);
    int years = latest.getReportDate().getYear() - oldest.getReportDate().getYear();
    if (years <= 0) {
      return null;
    }
    double ratio =
        latest.getNetProfit().divide(oldest.getNetProfit(), 8, RoundingMode.HALF_UP).doubleValue();
    if (ratio <= 0) {
      return null;
    }
    double cagr = (Math.pow(ratio, 1.0 / years) - 1.0) * 100.0;
    return BigDecimal.valueOf(cagr).setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal computePeg(BigDecimal peTtm, BigDecimal cagrPct) {
    if (peTtm == null || cagrPct == null || cagrPct.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return peTtm.divide(cagrPct, 4, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
  }

  private String ratePeg(BigDecimal peg) {
    if (peg == null) {
      return "暂不适用";
    }
    if (peg.compareTo(BigDecimal.valueOf(0.5)) < 0) return "极度低估";
    if (peg.compareTo(BigDecimal.ONE) < 0) return "低估";
    if (peg.compareTo(BigDecimal.valueOf(1.5)) < 0) return "合理";
    if (peg.compareTo(BigDecimal.valueOf(2.0)) < 0) return "偏贵";
    return "高估";
  }

  private BigDecimal computeDigestYears(BigDecimal peTtm, BigDecimal cagrPct) {
    if (peTtm == null
        || cagrPct == null
        || peTtm.compareTo(BigDecimal.valueOf(30)) <= 0
        || cagrPct.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    double cagrDecimal =
        cagrPct.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP).doubleValue();
    double years =
        Math.log(peTtm.divide(BigDecimal.valueOf(30), MathContext.DECIMAL64).doubleValue())
            / Math.log(1 + cagrDecimal);
    return BigDecimal.valueOf(years).setScale(1, RoundingMode.HALF_UP);
  }

  private String firstSector(String sectorNames) {
    if (sectorNames == null || sectorNames.isBlank()) {
      return "";
    }
    String[] parts = sectorNames.split("[,，]+");
    return parts.length == 0 ? "" : parts[0].trim();
  }

  private BigDecimal median(List<BigDecimal> values) {
    if (values.isEmpty()) {
      return BigDecimal.ZERO;
    }
    int mid = values.size() / 2;
    if (values.size() % 2 == 1) {
      return values.get(mid).setScale(2, RoundingMode.HALF_UP);
    }
    return values
        .get(mid - 1)
        .add(values.get(mid))
        .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
  }

  private record QuoteMetrics(
      BigDecimal price,
      BigDecimal marketCap,
      BigDecimal cagrPct,
      BigDecimal peg,
      String pegRating,
      BigDecimal digestYears,
      String valuationVerdict, // 10xPS: 低估/合理/泡沫/—
      String valuationCommentary,
      BigDecimal valuationDeviationPct,
      String valuationDeviationRef,
      String valuationDeviationLabel) {}
}
