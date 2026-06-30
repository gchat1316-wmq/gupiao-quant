package com.quant.controller;

import com.quant.dto.QuoteDTO;
import com.quant.dto.QuotePageDTO;
import com.quant.service.QuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService quoteService;

    @GetMapping
    public QuotePageDTO search(
            @RequestParam(defaultValue = "") String kw,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return quoteService.search(kw, page, size);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public QuoteDTO create(@RequestBody QuoteDTO req) {
        return quoteService.create(req);
    }

    @PostMapping("/batch")
    @PreAuthorize("hasRole('ADMIN')")
    public List<QuoteDTO> batchCreate(@RequestBody List<String> lines) {
        return quoteService.batchCreate(lines);
    }

    @PostMapping("/{id}/like")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Boolean> like(@PathVariable Long id) {
        quoteService.like(id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/import")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Long> importToStudy(@PathVariable Long id) {
        Long nodeId = quoteService.importToStudy(id);
        return Map.of("nodeId", nodeId);
    }
}
