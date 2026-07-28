package com.quant.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.quant.dto.trendwave.*;
import com.quant.security.UserPrincipal;
import com.quant.service.trendwave.TrendWaveService;
import com.quant.service.trendwave.TrendWaveStatsService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/trend-wave")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TrendWaveController {

  private final TrendWaveService service;
  private final TrendWaveStatsService statsService;

  @PostMapping("/pool")
  public MoneyPoolItemDTO addPool(
      @RequestBody MoneyPoolAddRequest req, @AuthenticationPrincipal UserPrincipal principal) {
    return service.addToPool(req, userId(principal));
  }

  @GetMapping("/pool")
  public List<MoneyPoolItemDTO> listPool(@AuthenticationPrincipal UserPrincipal principal) {
    return service.listPool(userId(principal));
  }

  @DeleteMapping("/pool/{id}")
  public ResponseEntity<Void> removePool(
      @PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
    service.removeFromPool(id, userId(principal));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/watches")
  public List<MoneyWatchDTO> listWatches(@AuthenticationPrincipal UserPrincipal principal) {
    return service.listWatches(userId(principal));
  }

  @GetMapping("/watches/{id}")
  public MoneyWatchDTO getWatch(@PathVariable Long id) {
    return service.getWatch(id);
  }

  @PostMapping("/watches/{id}/rescreen")
  public MoneyWatchDTO rescreen(@PathVariable Long id) {
    return service.rescreenWatch(id);
  }

  @PostMapping("/positions")
  public MoneyPositionDTO openPosition(
      @RequestBody MoneyPositionOpenRequest req, @AuthenticationPrincipal UserPrincipal principal) {
    return service.openPosition(req, userId(principal));
  }

  @PostMapping("/trades")
  public MoneyPositionDTO addTrade(
      @RequestBody MoneyTradeLegRequest req, @AuthenticationPrincipal UserPrincipal principal) {
    return service.addTradeLeg(req, userId(principal));
  }

  @GetMapping("/events")
  public List<MoneyEventDTO> events(@RequestParam(defaultValue = "50") int limit) {
    return service.listEvents(limit);
  }

  @PostMapping("/events/{id}/ack")
  public Map<String, Object> ack(@PathVariable Long id) {
    service.ackEvent(id);
    return Map.of("ok", true);
  }

  @PostMapping("/scan")
  public MoneyScanResultDTO scan(@RequestParam(defaultValue = "false") boolean eod) {
    return service.scan(eod);
  }

  @GetMapping("/stats")
  public MoneyStatsDTO stats(@AuthenticationPrincipal UserPrincipal principal) {
    return statsService.stats(userId(principal));
  }

  private long userId(UserPrincipal principal) {
    return principal == null || principal.getId() == null ? 0L : principal.getId();
  }
}
