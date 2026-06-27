package com.quant.controller;

import com.quant.dto.xieboinvest.XieboAnalysisDetailDTO;
import com.quant.dto.xieboinvest.XieboAnalysisListItemDTO;
import com.quant.dto.xieboinvest.XieboNewsDTO;
import com.quant.dto.xieboinvest.XieboQuoteDTO;
import com.quant.dto.xieboinvest.XieboWatchlistItemDTO;
import com.quant.service.xieboinvest.XieboInvestAnalysisService;
import com.quant.service.xieboinvest.XieboInvestNewsService;
import com.quant.service.xieboinvest.XieboInvestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/xiebo-invest")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class XieboInvestController {

    private final XieboInvestService service;
    @SuppressWarnings("unused")
    private final XieboInvestAnalysisService analysisService;
    @SuppressWarnings("unused")
    private final XieboInvestNewsService newsService;

    @GetMapping("/watchlist")
    public List<XieboWatchlistItemDTO> watchlist() {
        return service.getWatchlist();
    }

    @PostMapping("/watchlist")
    public List<XieboWatchlistItemDTO> addWatchlist(@RequestBody Map<String, String> body) {
        return service.addWatchlist(body.get("keyword"));
    }

    @DeleteMapping("/watchlist/{stockCode}")
    public Map<String, String> removeWatchlist(@PathVariable String stockCode) {
        service.removeWatchlist(stockCode);
        return Map.of("message", "removed");
    }

    @GetMapping("/quote")
    public XieboQuoteDTO quote(@RequestParam("keyword") String keyword) {
        return service.getQuote(keyword);
    }

    @GetMapping("/sector-pe")
    public Map<String, Object> sectorPe(@RequestParam("keyword") String keyword) {
        return service.getSectorPe(keyword);
    }

    @GetMapping("/news")
    public XieboNewsDTO news(@RequestParam(value = "keyword", required = false) String keyword) {
        return newsService.load(keyword);
    }

    @GetMapping("/analysis")
    public List<XieboAnalysisListItemDTO> analysisList() {
        return analysisService.list();
    }

    @PostMapping("/analysis")
    public XieboAnalysisDetailDTO createAnalysis(@RequestBody Map<String, String> body) {
        return analysisService.create(body.get("keyword"));
    }

    @GetMapping("/analysis/{id}")
    public XieboAnalysisDetailDTO analysisDetail(@PathVariable Long id) {
        return analysisService.detail(id);
    }
}
