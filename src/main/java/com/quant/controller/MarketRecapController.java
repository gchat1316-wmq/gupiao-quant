package com.quant.controller;

import com.quant.dto.marketrecap.MarketRecapDetailDTO;
import com.quant.dto.marketrecap.MarketRecapPageDTO;
import com.quant.service.MarketRecapService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/market-recaps")
@CrossOrigin(origins = "*")
public class MarketRecapController {

    private final MarketRecapService marketRecapService;

    public MarketRecapController(MarketRecapService marketRecapService) {
        this.marketRecapService = marketRecapService;
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
}
