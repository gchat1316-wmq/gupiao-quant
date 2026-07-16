package com.quant.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quant.dto.xieboinvest.XieboAnalysisDetailDTO;
import com.quant.dto.xieboinvest.XieboAnalysisListItemDTO;
import com.quant.dto.xieboinvest.XieboNewsDTO;
import com.quant.dto.xieboinvest.XieboQuoteDTO;
import com.quant.dto.xieboinvest.XieboWatchlistItemDTO;
import com.quant.dto.xieboinvest.XieboWeeklyOpportunitySlotDTO;
import com.quant.dto.xieboinvest.XieboWeeklyOpportunityUpdateRequest;
import com.quant.service.xieboinvest.XieboInvestAnalysisService;
import com.quant.service.xieboinvest.XieboInvestNewsService;
import com.quant.service.xieboinvest.XieboInvestService;

import lombok.RequiredArgsConstructor;

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

  @SuppressWarnings("unused")
  private final com.quant.service.xieboinvest.XieboWeeklyOpportunityService
      weeklyOpportunityService;

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

  // ── 谢博投资 · 每周重点股票（3×3 卡片） ──

  /** 读取所有 3 个分类的 27 个 slot（已认证即可访问） */
  @GetMapping("/weekly-opportunity")
  public List<XieboWeeklyOpportunitySlotDTO> listAllWeeklyOpportunity() {
    return weeklyOpportunityService.listAll();
  }

  /** 读取单个分类的 9 个 slot（已认证即可访问） */
  @GetMapping("/weekly-opportunity/{poolType}")
  public List<XieboWeeklyOpportunitySlotDTO> getWeeklyOpportunity(@PathVariable String poolType) {
    return weeklyOpportunityService.get(poolType);
  }

  /** 全量替换某个分类的 9 个 slot（MANAGER + ADMIN） */
  @PutMapping("/weekly-opportunity/{poolType}")
  public List<XieboWeeklyOpportunitySlotDTO> updateWeeklyOpportunity(
      @PathVariable String poolType, @RequestBody XieboWeeklyOpportunityUpdateRequest req) {
    return weeklyOpportunityService.update(poolType, req);
  }
}
