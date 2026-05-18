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
            List<TradeStockFinancial> records = financialRepository
                    .findByStockCodeOrderByReportDateDesc(info.getStockCode())
                    .stream()
                    .limit(limit)
                    .collect(Collectors.toList());

            List<QuarterMetricDTO> quarterList = records.stream()
                    .map(this::toQuarterMetric)
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
        if (trimmed.matches("\\d{4,8}")) {
            Optional<TradeStockInfo> byCode = stockInfoRepository.findByStockCode(trimmed);
            if (byCode.isPresent()) {
                return byCode;
            }
            // fallback：代码不在 stock_info，但有财务数据，则返回合成对象
            List<TradeStockFinancial> fin = financialRepository
                    .findByStockCodeOrderByReportDateDesc(trimmed);
            if (!fin.isEmpty()) {
                TradeStockInfo synthetic = new TradeStockInfo();
                synthetic.setStockCode(trimmed);
                synthetic.setStockName(trimmed);
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

    private QuarterMetricDTO toQuarterMetric(TradeStockFinancial f) {
        LocalDate d = f.getReportDate();
        return QuarterMetricDTO.builder()
                .quarter(formatQuarter(d))
                .reportDate(d.toString())
                .grossMargin(f.getGrossMargin())
                .revenueYoy(f.getRevenueYoy())
                .deductedNetProfitYoy(f.getDeductedNetProfitYoy())
                .deductedNetProfitTtm(f.getDeductedNetProfitTtm())
                .build();
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
