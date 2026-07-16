package com.quant.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

import com.quant.dto.QuoteDTO;
import com.quant.dto.QuotePageDTO;
import com.quant.service.QuoteService;

import lombok.RequiredArgsConstructor;

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
  public QuoteDTO create(@RequestBody QuoteDTO req) {
    return quoteService.create(req);
  }

  @PostMapping("/batch")
  public List<QuoteDTO> batchCreate(@RequestBody List<String> lines) {
    return quoteService.batchCreate(lines);
  }

  @PostMapping("/{id}/like")
  public Map<String, Boolean> like(@PathVariable Long id) {
    quoteService.like(id);
    return Map.of("ok", true);
  }

  @PostMapping("/{id}/import")
  public Map<String, Long> importToStudy(@PathVariable Long id) {
    Long nodeId = quoteService.importToStudy(id);
    return Map.of("nodeId", nodeId);
  }

  @DeleteMapping("/{id}")
  public Map<String, Boolean> delete(@PathVariable Long id) {
    quoteService.delete(id);
    return Map.of("ok", true);
  }
}
