package com.quant.service.lynchinvest;

import com.quant.dto.lynchinvest.LynchQuoteDTO;
import com.quant.dto.lynchinvest.LynchWatchlistItemDTO;
import com.quant.entity.InvestLynchWatchlist;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.InvestLynchWatchlistRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.StockQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LynchInvestService {

    private static final BigDecimal YI = BigDecimal.valueOf(100_000_000L);

    private final InvestLynchWatchlistRepository watchlistRepository;
    private final TradeStockBasicRepository stockBasicRepository;
    private final TradeStockDailyRepository dailyRepository;
    private final TradeStockFinancialRepository financialRepository;
    private final StockQueryService stockQueryService;

    public List<LynchWatchlistItemDTO> getWatchlist() {
        List<InvestLynchWatchlist> rows = watchlistRepository.findAllByOrderByDisplayOrderAscCreatedAtAsc();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> codes = rows.stream().map(InvestLynchWatchlist::getStockCode).toList();
        Map<String, TradeStockBasic> basicMap = stockBasicRepository.findByStockCodeIn(codes).stream()
                .collect(Collectors.toMap(TradeStockBasic::getStockCode, Function.identity()));
        Map<String, TradeStockDaily> latestDailyMap = dailyRepository.findLatestByStockCodes(codes).stream()
                .collect(Collectors.toMap(TradeStockDaily::getStockCode, Function.identity()));

        return rows.stream()
                .map(row -> toWatchlistItem(row, basicMap.get(row.getStockCode()), latestDailyMap.get(row.getStockCode())))
                .toList();
    }

    public LynchQuoteDTO getQuote(String keyword) {
        TradeStockBasic basic = stockQueryService.resolveStock(keyword)
                .orElseThrow(() -> new IllegalArgumentException("未找到股票: " + keyword));
        TradeStockDaily latestDaily = dailyRepository.findFirstByStockCodeOrderByTradeDateDesc(basic.getStockCode()).orElse(null);
        return toQuote(basic, latestDaily);
    }

    @Transactional
    public List<LynchWatchlistItemDTO> addWatchlist(String keyword) {
        TradeStockBasic basic = stockQueryService.resolveStock(keyword)
                .orElseThrow(() -> new IllegalArgumentException("未找到股票: " + keyword));
        if (watchlistRepository.findByStockCode(basic.getStockCode()).isEmpty()) {
            InvestLynchWatchlist row = new InvestLynchWatchlist();
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
        TradeStockBasic basic = stockQueryService.resolveStock(keyword)
                .orElseThrow(() -> new IllegalArgumentException("未找到股票: " + keyword));
        String sectorName = firstSector(basic.getSectorNames());
        List<TradeStockBasic> peers = stockBasicRepository.findBySectorNameLike(sectorName).stream()
                .filter(item -> item.getPeTtm() != null && item.getPeTtm().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        List<String> codes = peers.stream().map(TradeStockBasic::getStockCode).toList();
        Map<String, TradeStockDaily> latestDailyMap = dailyRepository.findLatestByStockCodes(codes).stream()
                .collect(Collectors.toMap(TradeStockDaily::getStockCode, Function.identity()));

        List<Map<String, Object>> stocks = new ArrayList<>();
        for (TradeStockBasic peer : peers) {
            TradeStockDaily latestDaily = latestDailyMap.get(peer.getStockCode());
            BigDecimal price = latestDaily == null ? null : latestDaily.getClosePrice();
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
        stocks.sort(Comparator.comparing(
                row -> Optional.ofNullable((BigDecimal) row.get("marketCap")).orElse(BigDecimal.ZERO),
                Comparator.reverseOrder()
        ));

        List<BigDecimal> peValues = peers.stream()
                .map(TradeStockBasic::getPeTtm)
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0)
                .sorted()
                .toList();
        BigDecimal avgPe = peValues.isEmpty() ? BigDecimal.ZERO
                : peValues.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
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

    private LynchWatchlistItemDTO toWatchlistItem(InvestLynchWatchlist row, TradeStockBasic basic, TradeStockDaily latestDaily) {
        TradeStockBasic actualBasic = basic != null ? basic : fallbackBasic(row);
        QuoteMetrics metrics = buildMetrics(actualBasic, latestDaily);
        return LynchWatchlistItemDTO.builder()
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
                .build();
    }

    private LynchQuoteDTO toQuote(TradeStockBasic basic, TradeStockDaily latestDaily) {
        QuoteMetrics metrics = buildMetrics(basic, latestDaily);
        return LynchQuoteDTO.builder()
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
                .build();
    }

    private QuoteMetrics buildMetrics(TradeStockBasic basic, TradeStockDaily latestDaily) {
        BigDecimal price = latestDaily != null ? latestDaily.getClosePrice() : null;
        BigDecimal marketCap = computeMarketCap(basic, price);
        BigDecimal cagrPct = computeCagrPct(financialRepository.findByStockCodeOrderByReportDateDesc(basic.getStockCode()));
        BigDecimal peg = computePeg(basic.getPeTtm(), cagrPct);
        String pegRating = ratePeg(peg);
        BigDecimal digestYears = computeDigestYears(basic.getPeTtm(), cagrPct);
        return new QuoteMetrics(price, marketCap, cagrPct, peg, pegRating, digestYears);
    }

    private TradeStockBasic fallbackBasic(InvestLynchWatchlist row) {
        TradeStockBasic basic = new TradeStockBasic();
        basic.setStockCode(row.getStockCode());
        basic.setStockName(row.getStockName());
        return basic;
    }

    private BigDecimal computeMarketCap(TradeStockBasic basic, BigDecimal price) {
        if (basic == null || basic.getTotalShares() == null || price == null) {
            return null;
        }
        return price.multiply(BigDecimal.valueOf(basic.getTotalShares()))
                .divide(YI, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeCagrPct(Collection<TradeStockFinancial> records) {
        List<TradeStockFinancial> annual = records.stream()
                .filter(f -> f.getReportDate() != null
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
        double ratio = latest.getNetProfit().divide(oldest.getNetProfit(), 8, RoundingMode.HALF_UP).doubleValue();
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
        if (peTtm == null || cagrPct == null || peTtm.compareTo(BigDecimal.valueOf(30)) <= 0 || cagrPct.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        double cagrDecimal = cagrPct.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP).doubleValue();
        double years = Math.log(peTtm.divide(BigDecimal.valueOf(30), MathContext.DECIMAL64).doubleValue()) / Math.log(1 + cagrDecimal);
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
        return values.get(mid - 1).add(values.get(mid))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private record QuoteMetrics(BigDecimal price,
                                BigDecimal marketCap,
                                BigDecimal cagrPct,
                                BigDecimal peg,
                                String pegRating,
                                BigDecimal digestYears) {
    }
}
