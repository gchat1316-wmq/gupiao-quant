package com.quant.controller;

import com.quant.dto.journal.*;
import com.quant.entity.JournalTrade;
import com.quant.service.journal.JournalService;
import com.quant.service.journal.JournalStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/journal")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class JournalController {

    private final JournalService service;
    private final JournalStatsService stats;

    @PostMapping("/trades")
    public JournalTradeDTO create(@RequestBody JournalTradeCreateRequest req,
                                  @AuthenticationPrincipal UserDetails user) {
        return service.create(req, user != null ? user.getUsername() : "anonymous");
    }

    @PutMapping("/trades/{id}")
    public JournalTradeDTO update(@PathVariable Long id,
                                  @RequestBody JournalTradeUpdateRequest req) {
        return service.update(id, req);
    }

    @GetMapping("/trades/{id}")
    public JournalTradeDTO get(@PathVariable Long id) {
        return service.findOne(id);
    }

    @GetMapping("/trades")
    public Page<JournalTradeDTO> list(
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Boolean isOpen,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable p = PageRequest.of(page, Math.min(size, 100));
        return service.list(mode, isOpen, tag, from, to, p);
    }

    @GetMapping("/trades/open")
    public List<JournalTradeDTO> listOpen() {
        return service.listOpen();
    }

    @DeleteMapping("/trades/{id}")
    public org.springframework.http.ResponseEntity<Void> delete(@PathVariable Long id) {
        service.softDelete(id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public JournalStatsDTO stats(
            @RequestParam(required = false) String mode) {
        JournalTrade.Mode m = (mode == null || mode.isBlank())
                ? null : JournalTrade.Mode.valueOf(mode);
        return stats.stats(m);
    }

    @GetMapping("/equity-curve")
    public List<EquityCurvePoint> equityCurve(
            @RequestParam(required = false) String mode) {
        JournalTrade.Mode m = (mode == null || mode.isBlank())
                ? null : JournalTrade.Mode.valueOf(mode);
        return stats.equityCurve(m);
    }

    @GetMapping("/r-distribution")
    public List<RDistributionBucket> rDistribution(
            @RequestParam(required = false) String mode) {
        JournalTrade.Mode m = (mode == null || mode.isBlank())
                ? null : JournalTrade.Mode.valueOf(mode);
        return stats.rDistribution(m);
    }
}
