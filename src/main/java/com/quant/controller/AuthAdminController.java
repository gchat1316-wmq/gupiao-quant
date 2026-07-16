package com.quant.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.entity.User;
import com.quant.security.UserPrincipal;
import com.quant.service.AuthService;
import com.quant.service.AuthService.UserDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/auth")
@Slf4j
@RequiredArgsConstructor
public class AuthAdminController {

  private final AuthService authService;

  /** ADMIN 查看所有用户列表 */
  @GetMapping("/admin/users")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<?> listUsers() {
    return ResponseEntity.ok(authService.listUsers());
  }

  /** ADMIN 修改用户角色 */
  @PutMapping("/admin/users/{id}/role")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<?> updateUserRole(
      @PathVariable Long id,
      @RequestBody Map<String, String> req,
      @AuthenticationPrincipal UserPrincipal principal) {
    try {
      String roleStr = req.get("role").toUpperCase();
      UserDto updated =
          authService.updateUserRole(principal.getId(), id, User.Role.valueOf(roleStr));
      return ResponseEntity.ok(updated);
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  /** ADMIN 禁用/启用用户 */
  @PutMapping("/admin/users/{id}/disabled")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<?> toggleDisabled(
      @PathVariable Long id,
      @RequestBody Map<String, Boolean> req,
      @AuthenticationPrincipal UserPrincipal principal) {
    try {
      authService.toggleUserDisabled(
          principal.getId(), id, Boolean.TRUE.equals(req.get("disabled")));
      return ResponseEntity.ok(Map.of("message", "操作成功"));
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  /** ADMIN 更新指定用户的通知偏好 */
  @PutMapping("/admin/users/{id}/notify")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<?> adminUpdateUserNotify(
      @PathVariable Long id,
      @RequestBody Map<String, Boolean> req,
      @AuthenticationPrincipal UserPrincipal principal) {
    try {
      UserDto updated =
          authService.updateProfile(
              id,
              null,
              null,
              null,
              req.get("notifyWechat"),
              req.get("notifySms"),
              req.get("notifyPhone"),
              null);
      return ResponseEntity.ok(updated);
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }
}
