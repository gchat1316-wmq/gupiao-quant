package com.quant.controller;

import com.quant.dto.QueryResultDTO;
import com.quant.service.StockQueryService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stock")
@CrossOrigin(origins = "*")
public class StockController {

    private final StockQueryService stockQueryService;

    public StockController(StockQueryService stockQueryService) {
        this.stockQueryService = stockQueryService;
    }

    @GetMapping("/financial")
    public QueryResultDTO queryFinancial(@RequestParam("keywords") String keywords,
                                         @RequestParam(value = "quarters", required = false) Integer quarters) {
        return stockQueryService.query(keywords, quarters);
    }
}
