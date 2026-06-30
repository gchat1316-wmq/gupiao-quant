package com.quant.controller;

import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.PositionFillRequest;
import com.quant.dto.techai.PositionFillDTO;
import com.quant.dto.techai.TechAiAlertDTO;
import com.quant.dto.techai.TechAiPoolItemDTO;
import com.quant.service.TechAiService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tech-ai")
@CrossOrigin(origins = "*")
public class TechAiController {

    private final TechAiService techAiService;

    public TechAiController(TechAiService techAiService) {
        this.techAiService = techAiService;
    }

    @GetMapping("/pool")
    public List<TechAiPoolItemDTO> listPool() {
        return techAiService.listPool();
    }

    @PostMapping("/pool")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public TechAiPoolItemDTO addToPool(@RequestBody PoolSaveRequest request) {
        return techAiService.addToPool(request);
    }

    @PatchMapping("/pool/{id}/field")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public TechAiPoolItemDTO updateField(@PathVariable Integer id, @RequestBody PoolFieldUpdateRequest request) {
        return techAiService.updateField(id, request);
    }

    @DeleteMapping("/pool/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, String>> removeFromPool(@PathVariable Integer id) {
        techAiService.removeFromPool(id);
        return ResponseEntity.ok(Map.of("message", "已移除"));
    }

    @PostMapping("/pool/{id}/fill")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public TechAiPoolItemDTO recordFill(@PathVariable Integer id, @RequestBody PositionFillRequest request) {
        return techAiService.recordFill(id, request);
    }

    @GetMapping("/pool/{id}/fills")
    public List<PositionFillDTO> listFills(@PathVariable Integer id) {
        return techAiService.listFills(id);
    }

    @DeleteMapping("/pool/{id}/fills/{fillId}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public TechAiPoolItemDTO deleteFill(@PathVariable Integer id, @PathVariable Long fillId) {
        return techAiService.deleteFill(id, fillId);
    }

    @GetMapping("/alerts")
    public List<TechAiAlertDTO> listAlerts() {
        return techAiService.listAlerts();
    }

    @PostMapping("/monitor/run")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public Map<String, Object> runMonitor() {
        int triggered = techAiService.monitorQuotes();
        return Map.of("message", "monitor triggered", "triggered", triggered);
    }
}
