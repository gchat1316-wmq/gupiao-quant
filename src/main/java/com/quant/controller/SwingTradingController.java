package com.quant.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.quant.dto.swing.SwingFillConfirmRequest;
import com.quant.dto.swing.SwingSignalDTO;
import com.quant.dto.swing.SwingStatsDTO;
import com.quant.dto.swing.SwingWatchDTO;
import com.quant.dto.swing.SwingWatchPatchRequest;
import com.quant.dto.swing.SwingWatchRequest;
import com.quant.security.UserPrincipal;
import com.quant.service.swing.SwingScanService;
import com.quant.service.swing.SwingWatchService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/swing")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class SwingTradingController {

  private final SwingWatchService watchService;
  private final SwingScanService scanService;

  @PostMapping("/watch")
  @PreAuthorize("isAuthenticated()")
  public SwingWatchDTO add(
      @AuthenticationPrincipal UserPrincipal me, @RequestBody @Valid SwingWatchRequest req) {
    requireUser(me);
    try {
      return watchService.add(me.getId(), req);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping("/watch")
  @PreAuthorize("isAuthenticated()")
  public List<SwingWatchDTO> list(@AuthenticationPrincipal UserPrincipal me) {
    requireUser(me);
    return watchService.list(me.getId());
  }

  @GetMapping("/watch/{id}")
  @PreAuthorize("isAuthenticated()")
  public SwingWatchDTO get(@AuthenticationPrincipal UserPrincipal me, @PathVariable Long id) {
    requireUser(me);
    try {
      return watchService.get(me.getId(), id);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  @PatchMapping("/watch/{id}")
  @PreAuthorize("isAuthenticated()")
  public SwingWatchDTO patch(
      @AuthenticationPrincipal UserPrincipal me,
      @PathVariable Long id,
      @RequestBody SwingWatchPatchRequest req) {
    requireUser(me);
    try {
      return watchService.patch(me.getId(), id, req);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @DeleteMapping("/watch/{id}")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> delete(
      @AuthenticationPrincipal UserPrincipal me, @PathVariable Long id) {
    requireUser(me);
    try {
      watchService.delete(me.getId(), id);
      return Map.of("ok", true);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping("/signals")
  @PreAuthorize("isAuthenticated()")
  public List<SwingSignalDTO> signals(@AuthenticationPrincipal UserPrincipal me) {
    requireUser(me);
    return watchService.listSignals(me.getId());
  }

  @PostMapping("/signals/{id}/ack")
  @PreAuthorize("isAuthenticated()")
  public SwingSignalDTO ack(@AuthenticationPrincipal UserPrincipal me, @PathVariable Long id) {
    requireUser(me);
    try {
      return watchService.ackSignal(me.getId(), id);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  @PostMapping("/signals/{id}/execute")
  @PreAuthorize("isAuthenticated()")
  public SwingWatchDTO execute(
      @AuthenticationPrincipal UserPrincipal me,
      @PathVariable Long id,
      @RequestBody @Valid SwingFillConfirmRequest req) {
    requireUser(me);
    try {
      return watchService.confirmFill(me.getId(), id, req);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping("/stats")
  @PreAuthorize("isAuthenticated()")
  public SwingStatsDTO stats(@AuthenticationPrincipal UserPrincipal me) {
    requireUser(me);
    return watchService.stats(me.getId());
  }

  @PostMapping("/scan/run")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> runScan(@AuthenticationPrincipal UserPrincipal me) {
    requireUser(me);
    var r = scanService.scanAll(true);
    return Map.of(
        "ok",
        true,
        "scanned",
        r.scanned(),
        "setups",
        r.setups(),
        "signals",
        r.signals(),
        "fills",
        r.fills());
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of("ok", true, "module", "swing-trading");
  }

  private void requireUser(UserPrincipal me) {
    if (me == null || me.getId() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
  }
}
