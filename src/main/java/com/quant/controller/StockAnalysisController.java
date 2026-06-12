package com.quant.controller;

import com.quant.dto.stockanalysis.StockAnalysisRequest;
import com.quant.dto.stockanalysis.StockAnalysisResponse;
import com.quant.service.StockAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/stock-analysis")
@RequiredArgsConstructor
public class StockAnalysisController {

    private final StockAnalysisService service;

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody StockAnalysisRequest req) {
        if (req.getCode() == null || req.getCode().trim().isEmpty()) {
            return error("股票代码不能为空");
        }
        log.info("个股分析请求: code={} method={}", req.getCode(), req.getMethod());
        StockAnalysisResponse resp = service.analyze(req);
        Map<String, Object> wrapper = new HashMap<>();
        if (!resp.isOk()) {
            wrapper.put("ok", false);
            wrapper.put("code", 500);
            wrapper.put("message", "数据获取失败, 请检查股票代码或稍后重试");
            return wrapper;
        }
        wrapper.put("ok", true);
        wrapper.put("data", resp);
        return wrapper;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("status", "running");
        r.put("service", "stock-analysis");
        r.put("dataSource", "baostock");
        return r;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("ok", false);
        r.put("code", 400);
        r.put("message", message);
        return r;
    }
}
