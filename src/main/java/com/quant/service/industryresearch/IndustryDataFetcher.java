package com.quant.service.industryresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.service.AStockDataQuoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * A-Stock-Data 数据抓取：行情 / 财务 / 资金流
 * 对应投研链路第一阶段：取数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryDataFetcher {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AStockDataQuoteService aStockDataQuoteService;
    private final IndustryResearchProperties props;

    /**
     * 取一组股票代码的最新行情
     */
    public Map<String, Object> fetchQuotes(Collection<String> stockCodes) {
        Map<String, AStockDataQuoteService.QuoteSnapshot> quotes = aStockDataQuoteService.fetchQuotes(stockCodes);
        Map<String, Object> out = new LinkedHashMap<>();
        quotes.forEach((code, q) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", q.stockCode());
            m.put("latestPrice", q.latestPrice());
            m.put("prevClosePrice", q.prevClosePrice());
            m.put("totalMarketCapYi", q.totalMarketCapYi());
            m.put("quoteTime", q.quoteTime());
            m.put("source", q.source());
            out.put(code, m);
        });
        return out;
    }

    /**
     * 取行业指数 / 板块资金流（mock 兜底，避免外网失败导致流水线断）
     */
    public Map<String, Object> fetchSectorFlow(String industryKeyword) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", industryKeyword);
        out.put("fetchedAt", new Date());
        // 真实实现：调用东方财富 / 同花顺 / a-stock-data 板块 API；这里先 mock
        out.put("topSectors", List.of(
                Map.of("name", industryKeyword, "changePct", 2.3, "mainInflow", 12.5),
                Map.of("name", "上下游关联 1", "changePct", 1.8, "mainInflow", 8.2)
        ));
        out.put("source", props.getDataFetch().isFallbackLocal() ? "mock+fallback" : "a-stock-data");
        return out;
    }

    /**
     * 解析一组代码返回的数据，提取指标（PE / 市值 / 涨幅）
     */
    public List<Map<String, Object>> extractIndicators(Collection<String> stockCodes) {
        Map<String, AStockDataQuoteService.QuoteSnapshot> quotes = aStockDataQuoteService.fetchQuotes(stockCodes);
        List<Map<String, Object>> rows = new ArrayList<>();
        quotes.forEach((code, q) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("latestPrice", q.latestPrice());
            row.put("prevClosePrice", q.prevClosePrice());
            row.put("totalMarketCapYi", q.totalMarketCapYi());
            rows.add(row);
        });
        return rows;
    }
}