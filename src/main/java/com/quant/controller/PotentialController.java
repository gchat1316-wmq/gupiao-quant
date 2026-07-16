package com.quant.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.PositionFillRequest;
import com.quant.dto.techai.PositionFillDTO;
import com.quant.dto.techai.TechAiAlertDTO;
import com.quant.dto.techai.TechAiPoolItemDTO;
import com.quant.service.potential.PotentialService;

@RestController
@RequestMapping("/api/potential")
@CrossOrigin(origins = "*")
public class PotentialController {

  private final PotentialService potentialService;

  public PotentialController(PotentialService potentialService) {
    this.potentialService = potentialService;
  }

  @GetMapping("/pool")
  public List<TechAiPoolItemDTO> listPool() {
    return potentialService.listPool();
  }

  @PostMapping("/pool")
  public TechAiPoolItemDTO addToPool(@RequestBody PoolSaveRequest request) {
    return potentialService.addToPool(request);
  }

  @PatchMapping("/pool/{id}/field")
  public TechAiPoolItemDTO updateField(
      @PathVariable Integer id, @RequestBody PoolFieldUpdateRequest request) {
    return potentialService.updateField(id, request);
  }

  @DeleteMapping("/pool/{id}")
  public ResponseEntity<Map<String, String>> removeFromPool(@PathVariable Integer id) {
    potentialService.removeFromPool(id);
    return ResponseEntity.ok(Map.of("message", "已移除"));
  }

  @PostMapping("/pool/{id}/fill")
  public TechAiPoolItemDTO recordFill(
      @PathVariable Integer id, @RequestBody PositionFillRequest request) {
    return potentialService.recordFill(id, request);
  }

  @GetMapping("/pool/{id}/fills")
  public List<PositionFillDTO> listFills(@PathVariable Integer id) {
    return potentialService.listFills(id);
  }

  @DeleteMapping("/pool/{id}/fills/{fillId}")
  public TechAiPoolItemDTO deleteFill(@PathVariable Integer id, @PathVariable Long fillId) {
    return potentialService.deleteFill(id, fillId);
  }

  @GetMapping("/alerts")
  public List<TechAiAlertDTO> listAlerts() {
    return potentialService.listAlerts();
  }

  @PostMapping("/monitor/run")
  public Map<String, Object> runMonitor() {
    int triggered = potentialService.monitorQuotes();
    return Map.of("message", "monitor triggered", "triggered", triggered);
  }
}
