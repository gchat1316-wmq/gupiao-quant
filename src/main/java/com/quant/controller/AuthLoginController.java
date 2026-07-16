package com.quant.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.entity.User;
import com.quant.security.UserPrincipal;
import com.quant.service.AuthService;
import com.quant.service.AuthService.AuthResult;
import com.quant.service.AuthService.UserDto;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/auth")
@Slf4j
@RequiredArgsConstructor
public class AuthLoginController {

  private final AuthService authService;

  public record LoginRequest(String phone, String username, String password) {}

  public record VerifyCodeRequest(String phone, String code) {}

  public record AuthResponse(
      String accessToken, String tokenType, boolean isNewUser, UserDto user) {}

  @PostMapping("/login")
  public ResponseEntity<?> login(@RequestBody LoginRequest req, HttpServletRequest httpReq) {
    String ip = getClientIp(httpReq);
    try {
      // 支持 phone 或 username 登录
      String identifier = req.phone() != null ? req.phone() : req.username();
      AuthResult result = authService.loginWithPassword(identifier, req.password(), ip);
      return ResponseEntity.ok(
          new AuthResponse(result.token(), "bearer", result.isNewUser(), result.toDto()));
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/verify-code")
  public ResponseEntity<?> verifyCode(
      @RequestBody VerifyCodeRequest req, HttpServletRequest httpReq) {
    String ip = getClientIp(httpReq);
    try {
      AuthResult result = authService.verifyCode(req.phone(), req.code(), ip);
      return ResponseEntity.ok(
          new AuthResponse(result.token(), "bearer", result.isNewUser(), result.toDto()));
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  /** 用登录码注册/登录（公开接口） */
  @PostMapping("/login-code")
  public ResponseEntity<?> loginWithCode(
      @RequestBody Map<String, String> req, HttpServletRequest httpReq) {
    String code = req.get("code");
    if (code == null || code.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "登录码不能为空"));
    }
    String ip = getClientIp(httpReq);
    try {
      AuthResult result = authService.loginWithCode(code, ip);
      return ResponseEntity.ok(new AuthResponse(result.token(), "bearer", true, result.toDto()));
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  /** ADMIN 生成登录码，可指定角色和有效期 */
  @PostMapping("/admin/login-code")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<?> generateLoginCode(
      @RequestBody Map<String, Object> req, @AuthenticationPrincipal UserPrincipal principal) {
    try {
      String roleStr = (String) req.get("role");
      User.Role role = User.Role.valueOf(roleStr.toUpperCase());
      int expireDays =
          req.containsKey("expireDays") ? ((Number) req.get("expireDays")).intValue() : 7;
      String code = authService.generateLoginCode(principal.getId(), role, expireDays);
      return ResponseEntity.ok(Map.of("code", code, "role", role.name(), "expireDays", expireDays));
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  private String getClientIp(HttpServletRequest req) {
    String xff = req.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].trim();
    }
    return req.getRemoteAddr();
  }
}
