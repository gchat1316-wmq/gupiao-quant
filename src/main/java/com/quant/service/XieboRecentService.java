package com.quant.service;

import com.quant.dto.xiebo.RecentNoteDto;
import com.quant.dto.xiebo.RecentWatchDto;
import com.quant.entity.InvestXieboRecentWatch;
import com.quant.repository.InvestXieboRecentWatchRepository;
import com.quant.repository.InvestXieboStockNoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class XieboRecentService {

    private final InvestXieboRecentWatchRepository watchRepo;
    private final InvestXieboStockNoteRepository noteRepo;
    private final AStockDataQuoteService quoteService;

    @Transactional(readOnly = true)
    public List<RecentWatchDto> listAll(String type) {
        List<InvestXieboRecentWatch> rows = (type == null || type.isBlank())
                ? watchRepo.findAllByOrderByCreatedAtDesc()
                : watchRepo.findByTypeOrderByCreatedAtDesc(type);
        if (rows == null || rows.isEmpty()) return List.of();

        // 1 次批量拉价
        List<String> codes = rows.stream().map(InvestXieboRecentWatch::getStockCode).toList();
        Map<String, AStockDataQuoteService.QuoteSnapshot> quotes;
        try {
            quotes = quoteService.fetchQuotes(codes);
        } catch (Exception e) {
            log.warn("批量拉价失败,currentPrice 留空: {}", e.getMessage());
            quotes = Map.of();
        }

        // 1 次批量查 note 是否存在
        Map<String, Boolean> noteMap = new HashMap<>();
        for (InvestXieboRecentWatch w : rows) {
            noteMap.put(w.getStockCode(), noteRepo.findById(w.getStockCode()).isPresent());
        }

        List<RecentWatchDto> out = new ArrayList<>();
        for (InvestXieboRecentWatch w : rows) {
            AStockDataQuoteService.QuoteSnapshot q = quotes.get(w.getStockCode());
            out.add(RecentWatchDto.of(w,
                    q == null ? null : q.latestPrice(),
                    q == null ? null : q.prevClosePrice(),
                    noteMap.getOrDefault(w.getStockCode(), false)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public RecentNoteDto getNote(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            throw new IllegalArgumentException("stockCode 不能为空");
        }
        return noteRepo.findById(stockCode).map(RecentNoteDto::of).orElse(null);
    }
}
