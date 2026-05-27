package com.quant.service;

import com.quant.dto.QuarterMetricDTO;
import com.quant.dto.QueryResultDTO;
import com.quant.dto.StockBasicInfoDTO;
import com.quant.dto.StockFinancialDTO;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class StockQueryService {

    private static final int DEFAULT_QUARTERS = 15;

    private final TradeStockBasicRepository stockBasicRepository;
    private final TradeStockFinancialRepository financialRepository;

    public StockQueryService(TradeStockBasicRepository stockBasicRepository,
                             TradeStockFinancialRepository financialRepository) {
        this.stockBasicRepository = stockBasicRepository;
        this.financialRepository = financialRepository;
    }

    @Cacheable(value = "financial", key = "#keywords + '_' + (#quarters ?: 0)")
    @Transactional(readOnly = true)
    public QueryResultDTO query(String keywords, Integer quarters) {
        int limit = (quarters == null || quarters <= 0) ? DEFAULT_QUARTERS : quarters;

        List<String> tokens = parseKeywords(keywords);
        List<StockFinancialDTO> stocks = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        for (String token : tokens) {
            Optional<TradeStockBasic> basicOpt = resolveStock(token);
            if (basicOpt.isEmpty()) {
                notFound.add(token);
                continue;
            }
            TradeStockBasic basic = basicOpt.get();
            List<TradeStockFinancial> allRecords = financialRepository
                    .findByStockCodeOrderByReportDateDesc(basic.getStockCode());
            Map<LocalDate, TradeStockFinancial> dateMap = allRecords.stream()
                    .collect(Collectors.toMap(TradeStockFinancial::getReportDate, r -> r, (a, b) -> a));
            List<TradeStockFinancial> records = allRecords.stream().limit(limit).collect(Collectors.toList());

            List<QuarterMetricDTO> quarterList = records.stream()
                    .map(f -> toQuarterMetric(f, dateMap))
                    .collect(Collectors.toList());

            stocks.add(StockFinancialDTO.builder()
                    .stockCode(basic.getStockCode())
                    .stockName(basic.getStockName())
                    .basicInfo(toBasicInfoDTO(basic))
                    .quarters(quarterList)
                    .build());
        }

        return QueryResultDTO.builder()
                .requested(tokens.size())
                .matched(stocks.size())
                .notFound(notFound)
                .stocks(stocks)
                .build();
    }

    public Optional<TradeStockBasic> resolveStock(String token) {
        String trimmed = token.trim();
        if (trimmed.isEmpty()) return Optional.empty();

        String bareCode = trimmed.contains(".") ? trimmed.substring(0, trimmed.indexOf('.')) : trimmed;

        if (bareCode.matches("\\d{4,8}")) {
            // 带后缀精确匹配
            Optional<TradeStockBasic> byFull = stockBasicRepository.findByStockCode(trimmed);
            if (byFull.isPresent()) return byFull;
            // 裸代码前缀匹配（600519 → 600519.SH / 600519.SZ）
            List<TradeStockBasic> byPrefix = stockBasicRepository.findByStockCodePrefix(bareCode);
            if (!byPrefix.isEmpty()) return Optional.of(byPrefix.get(0));
            // fallback：查财务数据
            List<TradeStockFinancial> fin = financialRepository.findByStockCodeOrderByReportDateDesc(trimmed);
            if (!fin.isEmpty()) return Optional.of(syntheticBasic(trimmed, fin.get(0).getStockName()));
        }

        List<TradeStockBasic> byName = stockBasicRepository.findByStockNameLike(trimmed);
        if (!byName.isEmpty()) return Optional.of(byName.get(0));

        List<TradeStockFinancial> finByName = financialRepository.findByStockNameLike(trimmed);
        if (!finByName.isEmpty()) {
            TradeStockFinancial first = finByName.get(0);
            return Optional.of(syntheticBasic(first.getStockCode(), first.getStockName()));
        }
        return Optional.empty();
    }

    private TradeStockBasic syntheticBasic(String code, String name) {
        TradeStockBasic b = new TradeStockBasic();
        b.setStockCode(code);
        b.setStockName(name != null && !name.isBlank() ? name : code);
        return b;
    }

    private StockBasicInfoDTO toBasicInfoDTO(TradeStockBasic b) {
        String[] industries = parseSectorNames(b.getSectorNames());
        String industry = industries.length > 0 ? industries[0] : null;
        int extraCount = Math.max(0, industries.length - 1);

        String listDateStr = null;
        int listYears = 0;
        if (b.getListDate() != null) {
            listDateStr = b.getListDate().toString();
            listYears = (int) ChronoUnit.YEARS.between(b.getListDate(), LocalDate.now());
        }

        String updatedAt = null;
        if (b.getUpdatedAt() != null) {
            updatedAt = formatUpdatedAt(b.getUpdatedAt());
        }

        return StockBasicInfoDTO.builder()
                .stockCode(b.getStockCode())
                .stockName(b.getStockName())
                .exchange(b.getExchange())
                .board(deriveBoard(b.getStockCode()))
                .industry(industry)
                .extraIndustryCount(extraCount)
                .listDate(listDateStr)
                .listYears(listYears)
                .peTtm(b.getPeTtm())
                .pb(b.getPb())
                .psTtm(b.getPsTtm())
                .valuationLevel(b.getValuationLevel())
                .dataSource(b.getDataSource())
                .updatedAt(updatedAt)
                .build();
    }

    private String[] parseSectorNames(String sectorNames) {
        if (sectorNames == null || sectorNames.isBlank()) return new String[0];
        return Arrays.stream(sectorNames.split("[,，]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    /** 按股票代码前缀推断板块类型。 */
    static String deriveBoard(String stockCode) {
        if (stockCode == null) return null;
        String bare = stockCode.contains(".") ? stockCode.substring(0, stockCode.indexOf('.')) : stockCode;
        if (bare.startsWith("688")) return "科创板";
        if (bare.startsWith("300") || bare.startsWith("301")) return "创业板";
        if (bare.startsWith("8") || bare.startsWith("4")) return "北交所";
        if (bare.startsWith("6")) return "沪主板";
        if (bare.startsWith("0") || bare.startsWith("00")) return "深主板";
        if (bare.startsWith("2")) return "深主板";
        return null;
    }

    private String formatUpdatedAt(LocalDateTime dt) {
        long minutesAgo = ChronoUnit.MINUTES.between(dt, LocalDateTime.now());
        if (minutesAgo < 60) return minutesAgo <= 1 ? "刚刚更新" : minutesAgo + "分钟前更新";
        long hoursAgo = minutesAgo / 60;
        if (hoursAgo < 24) return hoursAgo + "小时前更新";
        long daysAgo = hoursAgo / 24;
        if (daysAgo == 1) return "昨日更新";
        if (daysAgo < 7) return daysAgo + "天前更新";
        return DateTimeFormatter.ofPattern("MM-dd").format(dt) + "更新";
    }

    private List<String> parseKeywords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,，;； \t]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private QuarterMetricDTO toQuarterMetric(TradeStockFinancial f,
                                              Map<LocalDate, TradeStockFinancial> allData) {
        LocalDate d = f.getReportDate();
        BigDecimal revenueYoy = f.getRevenueYoy() != null ? f.getRevenueYoy()
                : calcYoy(f.getRevenue(), allData.get(d.minusYears(1)) != null
                    ? allData.get(d.minusYears(1)).getRevenue() : null);
        BigDecimal profitYoy = f.getDeductedNetProfitYoy() != null ? f.getDeductedNetProfitYoy()
                : calcYoy(f.getNetProfit(), allData.get(d.minusYears(1)) != null
                    ? allData.get(d.minusYears(1)).getNetProfit() : null);
        return QuarterMetricDTO.builder()
                .quarter(formatQuarter(d))
                .reportDate(d.toString())
                .revenueYoy(revenueYoy)
                .deductedNetProfitYoy(profitYoy)
                .grossMargin(f.getGrossMargin())
                .netMargin(f.getNetMargin())
                .roe(f.getRoe())
                .roa(f.getRoa())
                .eps(f.getEps())
                .revenue(f.getRevenue())
                .netProfit(f.getNetProfit())
                .deductedNetProfitTtm(f.getDeductedNetProfitTtm())
                .totalAssets(f.getTotalAssets())
                .totalEquity(f.getTotalEquity())
                .operatingCashflow(f.getOperatingCashflow())
                .debtRatio(f.getDebtRatio())
                .currentRatio(f.getCurrentRatio())
                .build();
    }

    private BigDecimal calcYoy(BigDecimal current, BigDecimal prev) {
        if (current == null || prev == null || prev.compareTo(BigDecimal.ZERO) == 0) return null;
        return current.subtract(prev)
                .divide(prev.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private String formatQuarter(LocalDate d) {
        int year = d.getYear() % 100;
        int month = d.getMonthValue();
        int q;
        switch (month) {
            case 3 -> q = 1;
            case 6 -> q = 2;
            case 9 -> q = 3;
            case 12 -> q = 4;
            default -> q = (month - 1) / 3 + 1;
        }
        return String.format("%02dQ%d", year, q);
    }

    public List<String> buildUnifiedQuarterAxis(List<StockFinancialDTO> stocks) {
        Map<String, String> dateToQuarter = new LinkedHashMap<>();
        for (StockFinancialDTO s : stocks) {
            for (QuarterMetricDTO q : s.getQuarters()) {
                dateToQuarter.put(q.getReportDate(), q.getQuarter());
            }
        }
        LinkedHashSet<String> sortedDates = dateToQuarter.keySet().stream()
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return sortedDates.stream().map(dateToQuarter::get).collect(Collectors.toList());
    }
}
