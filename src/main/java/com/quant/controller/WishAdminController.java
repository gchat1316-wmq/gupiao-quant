package com.quant.controller;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quant.dto.wishpool.WishAdminDto;
import com.quant.dto.wishpool.WishReplyRequest;
import com.quant.entity.WishPool;
import com.quant.service.ai.WishPoolService;

import lombok.RequiredArgsConstructor;

/** 许愿池管理后台接口 — 全部 ADMIN 限定。 */
@RestController
@RequestMapping("/api/admin/wishes")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class WishAdminController {

  private final WishPoolService wishPoolService;

  /** 列表(分页+状态+关键字) */
  @GetMapping
  public Map<String, Object> list(
      @RequestParam(name = "status", required = false) WishPool.Status status,
      @RequestParam(name = "keyword", required = false) String keyword,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    return wishPoolService.listForAdmin(status, keyword, page, size);
  }

  /** 回复留言 */
  @PostMapping("/{id}/reply")
  public WishAdminDto reply(
      @PathVariable Long id,
      @RequestBody WishReplyRequest body,
      @AuthenticationPrincipal Object principal) {
    String replyBy = resolveActor(principal);
    return wishPoolService.reply(id, body == null ? null : body.getReply(), replyBy);
  }

  /** 切换右下角展示 */
  @PostMapping("/{id}/display")
  public WishAdminDto setDisplay(@PathVariable Long id, @RequestParam("display") boolean display) {
    return wishPoolService.setDisplay(id, display);
  }

  /** 删除 */
  @DeleteMapping("/{id}")
  public Map<String, String> delete(@PathVariable Long id) {
    wishPoolService.delete(id);
    return Map.of("message", "ok");
  }

  /** 状态/数量计数(用于页面顶部统计) */
  @GetMapping("/counts")
  public Map<String, Long> counts() {
    return wishPoolService.counts();
  }

  private String resolveActor(Object principal) {
    if (principal == null) return "admin";
    // principal 是 com.quant.security.UserPrincipal(String username, ...)
    try {
      var m = principal.getClass().getMethod("getUsername");
      Object u = m.invoke(principal);
      if (u != null) return String.valueOf(u);
    } catch (Exception ignore) {
      /* fallthrough */
    }
    return principal.toString();
  }
}
