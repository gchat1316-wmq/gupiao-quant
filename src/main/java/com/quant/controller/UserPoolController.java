package com.quant.controller;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.quant.entity.AuditLog;
import com.quant.entity.UserPool;
import com.quant.repository.AuditLogRepository;
import com.quant.repository.UserPoolRepository;
import com.quant.security.UserPrincipal;

@RestController
@RequestMapping("/api/pools")
public class UserPoolController {

  private final UserPoolRepository userPoolRepository;
  private final AuditLogRepository auditLogRepository;

  public UserPoolController(
      UserPoolRepository userPoolRepository, AuditLogRepository auditLogRepository) {
    this.userPoolRepository = userPoolRepository;
    this.auditLogRepository = auditLogRepository;
  }

  // ── 当前用户的全部股票池 ─────────────────────────────

  @GetMapping
  public ResponseEntity<?> listMyPools(@AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
    }
    List<UserPool> pools = userPoolRepository.findByUserId(principal.getId());
    return ResponseEntity.ok(pools.stream().map(this::toDto).toList());
  }

  // ── 创建股票池 ──────────────────────────────────────

  public record CreatePoolRequest(String poolName) {}

  @PostMapping
  @Transactional
  public ResponseEntity<?> createPool(
      @RequestBody CreatePoolRequest req, @AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
    }
    UserPool pool = new UserPool();
    pool.setUserId(principal.getId());
    pool.setPoolName(req.poolName() != null ? req.poolName() : "我的股票池");
    pool.setStocks(new ArrayList<>());
    pool.setIsPublic(false);
    pool = userPoolRepository.save(pool);

    log(
        principal.getId(),
        "CREATE_POOL",
        "pool:" + pool.getId(),
        Map.of("poolName", pool.getPoolName()));

    return ResponseEntity.status(201).body(toDto(pool));
  }

  // ── 获取单个股票池 ──────────────────────────────────

  @GetMapping("/{id}")
  public ResponseEntity<?> getPool(
      @PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
    }
    UserPool pool = userPoolRepository.findById(id).orElse(null);
    if (pool == null) {
      return ResponseEntity.status(404).body(Map.of("error", "股票池不存在"));
    }
    if (!pool.getUserId().equals(principal.getId()) && !principal.isAdmin()) {
      return ResponseEntity.status(403).body(Map.of("error", "无权访问"));
    }
    return ResponseEntity.ok(toDto(pool));
  }

  // ── 更新股票池（名称/公开状态） ─────────────────────

  public record UpdatePoolRequest(String poolName, Boolean isPublic) {}

  @PutMapping("/{id}")
  @Transactional
  public ResponseEntity<?> updatePool(
      @PathVariable Long id,
      @RequestBody UpdatePoolRequest req,
      @AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
    }
    UserPool pool = userPoolRepository.findById(id).orElse(null);
    if (pool == null) {
      return ResponseEntity.status(404).body(Map.of("error", "股票池不存在"));
    }
    if (!pool.getUserId().equals(principal.getId())) {
      return ResponseEntity.status(403).body(Map.of("error", "无权修改"));
    }
    if (req.poolName() != null) pool.setPoolName(req.poolName());
    if (req.isPublic() != null) pool.setIsPublic(req.isPublic());
    pool = userPoolRepository.save(pool);
    return ResponseEntity.ok(toDto(pool));
  }

  // ── 删除股票池 ─────────────────────────────────────

  @DeleteMapping("/{id}")
  @Transactional
  public ResponseEntity<?> deletePool(
      @PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
    }
    UserPool pool = userPoolRepository.findById(id).orElse(null);
    if (pool == null) {
      return ResponseEntity.status(404).body(Map.of("error", "股票池不存在"));
    }
    if (!pool.getUserId().equals(principal.getId()) && !principal.isAdmin()) {
      return ResponseEntity.status(403).body(Map.of("error", "无权删除"));
    }
    userPoolRepository.delete(pool);
    log(principal.getId(), "DELETE_POOL", "pool:" + id, null);
    return ResponseEntity.noContent().build();
  }

  // ── 添加股票 ───────────────────────────────────────

  public record AddStockRequest(String code, String name, String note) {}

  @PostMapping("/{id}/stocks")
  @Transactional
  public ResponseEntity<?> addStock(
      @PathVariable Long id,
      @RequestBody AddStockRequest req,
      @AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
    }
    UserPool pool = userPoolRepository.findById(id).orElse(null);
    if (pool == null) {
      return ResponseEntity.status(404).body(Map.of("error", "股票池不存在"));
    }
    if (!pool.getUserId().equals(principal.getId())) {
      return ResponseEntity.status(403).body(Map.of("error", "无权操作"));
    }

    List<UserPool.StockItem> stocks = pool.getStocks();
    if (stocks == null) stocks = new ArrayList<>();
    if (stocks.stream().anyMatch(s -> s.getCode().equals(req.code()))) {
      return ResponseEntity.badRequest().body(Map.of("error", "该股票已在池中"));
    }

    UserPool.StockItem item = new UserPool.StockItem();
    item.setCode(req.code());
    item.setName(req.name());
    item.setNote(req.note());
    item.setAddedAt(LocalDateTime.now().toString());
    stocks.add(item);
    pool.setStocks(stocks);
    pool = userPoolRepository.save(pool);

    log(
        principal.getId(),
        "ADD_STOCK",
        "pool:" + id,
        Map.of("code", req.code(), "name", req.name()));

    return ResponseEntity.ok(toDto(pool));
  }

  // ── 移除股票 ───────────────────────────────────────

  @DeleteMapping("/{id}/stocks/{code}")
  @Transactional
  public ResponseEntity<?> removeStock(
      @PathVariable Long id,
      @PathVariable String code,
      @AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
    }
    UserPool pool = userPoolRepository.findById(id).orElse(null);
    if (pool == null) {
      return ResponseEntity.status(404).body(Map.of("error", "股票池不存在"));
    }
    if (!pool.getUserId().equals(principal.getId())) {
      return ResponseEntity.status(403).body(Map.of("error", "无权操作"));
    }

    List<UserPool.StockItem> stocks = pool.getStocks();
    if (stocks == null) stocks = new ArrayList<>();
    int before = stocks.size();
    stocks.removeIf(s -> s.getCode().equals(code));
    if (stocks.size() == before) {
      return ResponseEntity.status(404).body(Map.of("error", "股票不在池中"));
    }

    pool.setStocks(stocks);
    userPoolRepository.save(pool);
    log(principal.getId(), "REMOVE_STOCK", "pool:" + id, Map.of("code", code));

    return ResponseEntity.noContent().build();
  }

  // ── 修改股票备注 ────────────────────────────────────

  @PutMapping("/{id}/stocks/{code}")
  @Transactional
  public ResponseEntity<?> updateStockNote(
      @PathVariable Long id,
      @PathVariable String code,
      @RequestBody Map<String, String> body,
      @AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
    }
    UserPool pool = userPoolRepository.findById(id).orElse(null);
    if (pool == null) {
      return ResponseEntity.status(404).body(Map.of("error", "股票池不存在"));
    }
    if (!pool.getUserId().equals(principal.getId())) {
      return ResponseEntity.status(403).body(Map.of("error", "无权操作"));
    }

    List<UserPool.StockItem> stocks = pool.getStocks();
    if (stocks == null) stocks = new ArrayList<>();
    boolean found = false;
    for (UserPool.StockItem s : stocks) {
      if (s.getCode().equals(code)) {
        s.setNote(body.get("note"));
        found = true;
        break;
      }
    }
    if (!found) {
      return ResponseEntity.status(404).body(Map.of("error", "股票不在池中"));
    }

    pool.setStocks(stocks);
    userPoolRepository.save(pool);

    return ResponseEntity.ok(toDto(pool));
  }

  // ── 工具 ────────────────────────────────────────────

  private Object toDto(UserPool pool) {
    return Map.of(
        "id", pool.getId(),
        "userId", pool.getUserId(),
        "poolName", pool.getPoolName() != null ? pool.getPoolName() : "",
        "stocks", pool.getStocks() != null ? pool.getStocks() : new ArrayList<>(),
        "isPublic", pool.getIsPublic() != null ? pool.getIsPublic() : false,
        "createdAt", pool.getCreatedAt() != null ? pool.getCreatedAt().toString() : "",
        "updatedAt", pool.getUpdatedAt() != null ? pool.getUpdatedAt().toString() : "");
  }

  private void log(Long userId, String action, String target, Map<String, Object> detail) {
    AuditLog entry = new AuditLog();
    entry.setUserId(userId);
    entry.setAction(action);
    entry.setTarget(target);
    entry.setDetail(detail);
    auditLogRepository.save(entry);
  }
}
