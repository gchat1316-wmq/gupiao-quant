package com.quant.controller;

import com.quant.dto.lynchinvest.LynchAnalysisDetailDTO;
import com.quant.dto.lynchinvest.LynchAnalysisListItemDTO;
import com.quant.dto.lynchinvest.LynchNewsDTO;
import com.quant.dto.lynchinvest.LynchQuoteDTO;
import com.quant.dto.lynchinvest.LynchWatchlistItemDTO;
import com.quant.service.lynchinvest.LynchInvestAnalysisService;
import com.quant.service.lynchinvest.LynchInvestNewsService;
import com.quant.service.lynchinvest.LynchInvestService;
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
@RequestMapping("/api/lynch-invest")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class LynchInvestController {

    private final LynchInvestService service;
    @SuppressWarnings("unused")
    private final LynchInvestAnalysisService analysisService;
    @SuppressWarnings("unused")
    private final LynchInvestNewsService newsService;

    @GetMapping("/watchlist")
    public List<LynchWatchlistItemDTO> watchlist() {
        return service.getWatchlist();
    }

    @PostMapping("/watchlist")
    public List<LynchWatchlistItemDTO> addWatchlist(@RequestBody Map<String, String> body) {
        return service.addWatchlist(body.get("keyword"));
    }

    @DeleteMapping("/watchlist/{stockCode}")
    public Map<String, String> removeWatchlist(@PathVariable String stockCode) {
        service.removeWatchlist(stockCode);
        return Map.of("message", "removed");
    }

    @GetMapping("/quote")
    public LynchQuoteDTO quote(@RequestParam("keyword") String keyword) {
        return service.getQuote(keyword);
    }

    @GetMapping("/sector-pe")
    public Map<String, Object> sectorPe(@RequestParam("keyword") String keyword) {
        return service.getSectorPe(keyword);
    }

    @GetMapping("/news")
    public LynchNewsDTO news(@RequestParam(value = "keyword", required = false) String keyword) {
        return newsService.load(keyword);
    }

    @GetMapping("/analysis")
    public List<LynchAnalysisListItemDTO> analysisList() {
        return analysisService.list();
    }

    @PostMapping("/analysis")
    public LynchAnalysisDetailDTO createAnalysis(@RequestBody Map<String, String> body) {
        return analysisService.create(body.get("keyword"));
    }

    @GetMapping("/analysis/{id}")
    public LynchAnalysisDetailDTO analysisDetail(@PathVariable Long id) {
        return analysisService.detail(id);
    }
}
