package com.quant.controller;

import com.quant.dto.invest.BigYangAlertDTO;
import com.quant.dto.invest.BigYangRunResultDTO;
import com.quant.dto.invest.BigYangSignalDTO;
import com.quant.dto.invest.BigYangSummaryDTO;
import com.quant.service.InvestBigYangSignalService;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/alerts")
    public List<BigYangAlertDTO> alerts() {
        return service.alerts();
    }

    @PostMapping("/alerts/{id}/read")
    public Map<String, String> read(@PathVariable Long id) {
        service.markAlertRead(id);
        return Map.of("message", "ok");
    }

    @PostMapping("/run")
    public BigYangRunResultDTO run() {
        return service.runManual();
    }
}
