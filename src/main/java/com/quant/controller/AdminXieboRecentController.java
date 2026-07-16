package com.quant.controller;

import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.dto.xiebo.AdminNoteUpdateRequest;
import com.quant.dto.xiebo.AdminRecentStockRequest;
import com.quant.entity.InvestXieboRecentWatch;
import com.quant.entity.InvestXieboStockNote;
import com.quant.repository.InvestXieboRecentWatchRepository;
import com.quant.repository.InvestXieboStockNoteRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/admin/xiebo/recent")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AdminXieboRecentController {

  private final InvestXieboRecentWatchRepository watchRepo;
  private final InvestXieboStockNoteRepository noteRepo;

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Map<String, Object>> create(
      @RequestBody @Valid AdminRecentStockRequest req) {
    if (watchRepo.existsById(req.getStockCode())) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "ok",
                  false,
                  "errorCode",
                  "DUPLICATE",
                  "errorMessage",
                  "股票已存在: " + req.getStockCode()));
    }
    InvestXieboRecentWatch w = new InvestXieboRecentWatch();
    w.setStockCode(req.getStockCode());
    w.setStockName(req.getStockName());
    w.setType(req.getType());
    watchRepo.save(w);
    return ResponseEntity.ok(Map.of("ok", true, "stockCode", w.getStockCode()));
  }

  @PutMapping("/{stockCode}")
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String, Object> update(
      @PathVariable String stockCode, @RequestBody @Valid AdminRecentStockRequest req) {
    InvestXieboRecentWatch w =
        watchRepo
            .findById(stockCode)
            .orElseThrow(() -> new IllegalArgumentException("股票不存在: " + stockCode));
    w.setStockName(req.getStockName());
    w.setType(req.getType());
    watchRepo.save(w);
    return Map.of("ok", true, "stockCode", stockCode);
  }

  @DeleteMapping("/{stockCode}")
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String, Object> delete(@PathVariable String stockCode) {
    watchRepo.deleteById(stockCode);
    return Map.of("ok", true, "stockCode", stockCode);
  }

  @PutMapping("/{stockCode}/note")
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String, Object> upsertNote(
      @PathVariable String stockCode, @RequestBody AdminNoteUpdateRequest req) {
    // JSoup sanitize: 允许基本标签 + 图片
    String sanitized =
        req.getNoteHtml() == null
            ? null
            : Jsoup.clean(req.getNoteHtml(), Safelist.basicWithImages());
    InvestXieboStockNote note =
        noteRepo
            .findById(stockCode)
            .orElseGet(
                () -> {
                  InvestXieboStockNote n = new InvestXieboStockNote();
                  n.setStockCode(stockCode);
                  return n;
                });
    note.setNoteHtml(sanitized);
    noteRepo.save(note);
    return Map.of("ok", true, "stockCode", stockCode);
  }
}
