package com.quant.controller;

import com.quant.dto.invest.BigYangAlertDTO;
import com.quant.dto.invest.BigYangQuoteDTO;
import com.quant.dto.invest.BigYangRunResultDTO;
import com.quant.dto.invest.BigYangSignalDTO;
import com.quant.dto.invest.BigYangSummaryDTO;
import com.quant.service.InvestBigYangSignalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invest/big-yang")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class InvestBigYangController {

    private final InvestBigYangSignalService service;

    @GetMapping("/summary")
    public BigYangSummaryDTO summary() {
        return service.summary();
    }

    @GetMapping("/signals")
    public List<BigYangSignalDTO> signals() {
        return service.signals();
    }

    /**
     * 实时行情（精简 DTO，配合 /signals 使用）。
     *
     * <p>前端先 GET /signals 拿到基础数据渲染表格，再异步 GET /signals/quotes 拿报价 Map，
     * 按 stockCode 合并到对应行；这样基础数据秒级展示，实时价异步填入不影响首屏。
     */
    @GetMapping("/signals/quotes")
    public List<BigYangQuoteDTO> signalsQuotes() {
        return service.signalsQuotes();
    }

    @GetMapping("/alerts")
    public List<BigYangAlertDTO> alerts() {
        return service.alerts();
    }

    @PostMapping("/alerts/{id}/read")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public Map<String, String> read(@PathVariable Long id) {
        service.markAlertRead(id);
        return Map.of("message", "ok");
    }

    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public BigYangRunResultDTO run() {
        return service.runManual();
    }
}
