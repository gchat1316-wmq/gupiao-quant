package com.quant.controller;

import com.quant.dto.marketrecap.MarketRecapBadgeDTO;
import com.quant.dto.marketrecap.MarketRecapDetailDTO;
import com.quant.dto.marketrecap.MarketRecapPageDTO;
import com.quant.service.DailyRecapService;
import com.quant.service.MarketRecapService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market-recaps")
@CrossOrigin(origins = "*")
public class MarketRecapController {

    private final MarketRecapService marketRecapService;
    private final DailyRecapService dailyRecapService;

    public MarketRecapController(MarketRecapService marketRecapService,
                                DailyRecapService dailyRecapService) {
        this.marketRecapService = marketRecapService;
        this.dailyRecapService = dailyRecapService;
    }

    @GetMapping("/markets")
    public List<String> listMarkets() {
        return marketRecapService.listMarkets();
    }

    @GetMapping
    public MarketRecapPageDTO getPage(@RequestParam(value = "market", required = false) String market) {
        return marketRecapService.getPage(market);
    }

    @GetMapping("/{id}")
    public MarketRecapDetailDTO getDetail(@PathVariable Long id) {
        return marketRecapService.getDetail(id);
    }

    @GetMapping("/badge")
    public MarketRecapBadgeDTO getBadge() {
        return marketRecapService.getBadgeSummary();
    }

    /** 手动触发 A 股复盘 */
    @GetMapping("/trigger/a-share")
    public Map<String, Object> triggerAShare() {
        Long id = dailyRecapService.triggerAShare();
        return Map.of("id", id, "message", "A 股复盘触发成功，id=" + id);
    }

    /** 手动触发美股复盘 */
    @GetMapping("/trigger/us-market")
    public Map<String, Object> triggerUsMarket() {
        Long id = dailyRecapService.triggerUsMarket();
        return Map.of("id", id, "message", "美股复盘触发成功，id=" + id);
    }
}
