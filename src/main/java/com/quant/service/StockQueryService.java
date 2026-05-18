package com.quant.service;

import com.quant.dto.QuarterMetricDTO;
import com.quant.dto.QueryResultDTO;
import com.quant.dto.StockFinancialDTO;
import com.quant.entity.TradeStockFinancial;
import com.quant.entity.TradeStockInfo;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.repository.TradeStockInfoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

    private final TradeStockInfoRepository stockInfoRepository;
    private final TradeStockFinancialRepository financialRepository;

    public StockQueryService(TradeStockInfoRepository stockInfoRepository,
                             TradeStockFinancialRepository financialRepository) {
        this.stockInfoRepository = stockInfoRepository;
        this.financialRepository = financialRepository;
    }

    @Transactional(readOnly = true)
    public QueryResultDTO query(String keywords, Integer quarters) {
        int limit = (quarters == null || quarters <= 0) ? DEFAULT_QUARTERS : quarters;

        List<String> tokens = parseKeywords(keywords);
        List<StockFinancialDTO> stocks = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        for (String token : tokens) {
            Optional<TradeStockInfo> infoOpt = resolveStock(token);
            if (infoOpt.isEmpty()) {
                notFound.add(token);
                continue;
            }
            TradeStockInfo info = infoOpt.get();
            List<TradeStockFinancial> allRecords = financialRepository
                    .findByStockCodeOrderByReportDateDesc(info.getStockCode());
            // 建立日期索引，用于计算同比
            Map<LocalDate, TradeStockFinancial> dateMap = allRecords.stream()
                    .collect(Collectors.toMap(TradeStockFinancial::getReportDate, r -> r, (a, b) -> a));
            List<TradeStockFinancial> records = allRecords.stream().limit(limit).collect(Collectors.toList());

            List<QuarterMetricDTO> quarterList = records.stream()
                    .map(f -> toQuarterMetric(f, dateMap))
                    .collect(Collectors.toList());

            stocks.add(StockFinancialDTO.builder()
                    .stockCode(info.getStockCode())
                    .stockName(info.getStockName())
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

    private Optional<TradeStockInfo> resolveStock(String token) {
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        // 兼容带交易所后缀，如 600519.SH → bareCode=600519
        String bareCode = trimmed.contains(".") ? trimmed.substring(0, trimmed.indexOf('.')) : trimmed;
        if (bareCode.matches("\\d{4,8}")) {
            Optional<TradeStockInfo> byCode = stockInfoRepository.findByStockCode(bareCode);
            if (byCode.isPresent()) {
                return byCode;
            }
            // fallback：查财务数据（支持裸代码和带后缀两种格式）
            List<TradeStockFinancial> fin = financialRepository
                    .findByStockCodeOrderByReportDateDesc(bareCode);
            if (!fin.isEmpty()) {
                TradeStockInfo synthetic = new TradeStockInfo();
                synthetic.setStockCode(bareCode);
                synthetic.setStockName(bareCode);
                return Optional.of(synthetic);
            }
        }
        List<TradeStockInfo> byName = stockInfoRepository.findByStockNameLike(trimmed);
        if (!byName.isEmpty()) {
            return Optional.of(byName.get(0));
        }
        return Optional.empty();
    }

    private List<String> parseKeywords(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("[,，;； \t]+");
        return Arrays.stream(parts)
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
                .grossMargin(f.getGrossMargin())
                .revenueYoy(revenueYoy)
                .deductedNetProfitYoy(profitYoy)
                .deductedNetProfitTtm(f.getDeductedNetProfitTtm())
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

    /**
     * 把多只股票的季度时间轴对齐到统一的横轴（按时间升序）。
     */
    public List<String> buildUnifiedQuarterAxis(List<StockFinancialDTO> stocks) {
        Map<String, String> dateToQuarter = new LinkedHashMap<>();
        Map<String, String> quarterToDate = new LinkedHashMap<>();
        for (StockFinancialDTO s : stocks) {
            for (QuarterMetricDTO q : s.getQuarters()) {
                dateToQuarter.put(q.getReportDate(), q.getQuarter());
                quarterToDate.put(q.getQuarter(), q.getReportDate());
            }
        }
        LinkedHashSet<String> sortedDates = dateToQuarter.keySet().stream()
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return sortedDates.stream().map(dateToQuarter::get).collect(Collectors.toList());
    }
}
