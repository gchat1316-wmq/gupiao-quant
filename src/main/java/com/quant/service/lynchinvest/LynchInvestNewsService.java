package com.quant.service.lynchinvest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.lynchinvest.LynchNewsDTO;
import com.quant.entity.InvestLynchWatchlist;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestLynchWatchlistRepository;
import com.quant.service.StockQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LynchInvestNewsService {

    private final InvestLynchWatchlistRepository watchlistRepository;
    private final StockQueryService stockQueryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LynchNewsDTO load(String keyword) {
        List<String> tickers = resolveTickers(keyword);
        try {
            ProcessBuilder pb = new ProcessBuilder(buildCommand(tickers));
            pb.directory(Path.of(System.getProperty("user.dir")).toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException(output.isBlank() ? "新闻抓取失败" : output);
            }
            Map<String, Object> raw = objectMapper.readValue(extractJson(output), new TypeReference<>() {});
            return LynchNewsDTO.builder()
                    .collectedAt((String) raw.get("collected_at"))
                    .stockNews(toItems((List<Map<String, Object>>) raw.get("stock_news")))
                    .announcements(toItems((List<Map<String, Object>>) raw.get("announcements")))
                    .marketNews(toItems((List<Map<String, Object>>) raw.get("market_news")))
                    .build();
        } catch (Exception e) {
            return LynchNewsDTO.builder()
                    .collectedAt(null)
                    .stockNews(List.of(LynchNewsDTO.NewsItemDTO.builder().category("stock").title("新闻抓取失败").content(e.getMessage()).source("system").build()))
                    .announcements(List.of())
                    .marketNews(List.of())
                    .build();
        }
    }

    private List<String> resolveTickers(String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            TradeStockBasic basic = stockQueryService.resolveStock(keyword)
                    .orElseThrow(() -> new IllegalArgumentException("未找到股票: " + keyword));
            return List.of(bareCode(basic.getStockCode()));
        }
        return watchlistRepository.findAllByOrderByDisplayOrderAscCreatedAtAsc().stream()
                .map(InvestLynchWatchlist::getStockCode)
                .map(this::bareCode)
                .distinct()
                .toList();
    }

    private List<String> buildCommand(List<String> tickers) {
        String python = "python3";
        String script = "scripts/lynch_collect_news.py";
        if (tickers.isEmpty()) {
            return List.of(python, script);
        }
        return List.of(python, script, String.join(",", tickers));
    }

    private String bareCode(String stockCode) {
        int idx = stockCode.indexOf('.');
        return idx >= 0 ? stockCode.substring(0, idx) : stockCode;
    }

    private List<LynchNewsDTO.NewsItemDTO> toItems(List<Map<String, Object>> rows) {
        if (rows == null) return List.of();
        return rows.stream().map(this::toItem).toList();
    }

    private String extractJson(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return output.substring(start, end + 1);
        }
        return output;
    }

    private LynchNewsDTO.NewsItemDTO toItem(Map<String, Object> row) {
        return LynchNewsDTO.NewsItemDTO.builder()
                .category(str(row.get("category")))
                .ticker(str(row.get("ticker")))
                .title(str(row.get("title")))
                .content(str(row.get("content")))
                .time(str(row.get("time")))
                .source(str(row.get("source")))
                .url(str(row.get("url")))
                .build();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
