package com.quant.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.security.UserPrincipal;
import com.quant.service.AuthService;
import com.quant.service.AuthService.AuthResult;
import com.quant.service.AuthService.UserDto;
import com.quant.service.EmailService;
import com.quant.service.SmsService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/auth")
@Slf4j
@RequiredArgsConstructor
public class AuthRegistrationController {

  private final AuthService authService;
  private final SmsService smsService;
  private final EmailService emailService;

  public record SendCodeRequest(String phone) {}

  public record SendEmailCodeRequest(String email) {}

  public record RegisterEmailRequest(String email, String password) {}

  public record VerifyEmailCodeRequest(String email, String code) {}

  public record ResetPasswordEmailRequest(String email, String code, String newPassword) {}

  public record ResetPasswordSmsRequest(String phone, String code, String newPassword) {}

  public record SetPasswordRequest(String password) {}

  public record AuthResponse(
      String accessToken, String tokenType, boolean isNewUser, UserDto user) {}

  @PostMapping("/send-code")
  public ResponseEntity<?> sendCode(@RequestBody SendCodeRequest req, HttpServletRequest httpReq) {
    String ip = getClientIp(httpReq);
    try {
      String code = authService.sendCode(req.phone(), ip);
      Map<String, Object> body = new HashMap<>();
      body.put("message", "验证码已发送");
      // dev/mock 模式（未配置真 SMS 服务商）：把验证码回给前端自动回填输入框
      // 真服务上线后 SmsService.isMock() == false → 不回传 → 前端不会自动回填
      if (smsService.isMock()) {
        body.put("code", code);
      }
      return ResponseEntity.ok(body);
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/send-email-code")
  public ResponseEntity<?> sendEmailCode(
      @RequestBody SendEmailCodeRequest req, HttpServletRequest httpReq) {
    String ip = getClientIp(httpReq);
    try {
      String code = authService.sendEmailCode(req.email(), ip);
      Map<String, Object> body = new HashMap<>();
      body.put("message", "验证码已发送");
      // dev/mock 模式（未配置真邮件服务）：把验证码回给前端自动回填输入框
      // 真服务上线后 EmailService.isMock() == false → 不回传 → 前端不会自动回填
      if (emailService.isMock()) {
        body.put("code", code);
      }
      return ResponseEntity.ok(body);
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/register-email")
  public ResponseEntity<?> registerEmail(
      @RequestBody RegisterEmailRequest req, HttpServletRequest httpReq) {
    String ip = getClientIp(httpReq);
    try {
      AuthResult result = authService.registerWithEmail(req.email(), req.password(), ip);
      return ResponseEntity.ok(
          new AuthResponse(result.token(), "bearer", result.isNewUser(), result.toDto()));
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/verify-email-code")
  public ResponseEntity<?> verifyEmailCode(
      @RequestBody VerifyEmailCodeRequest req, HttpServletRequest httpReq) {
    String ip = getClientIp(httpReq);
    try {
      AuthResult result = authService.verifyEmailCode(req.email(), req.code(), ip);
      return ResponseEntity.ok(
          new AuthResponse(result.token(), "bearer", result.isNewUser(), result.toDto()));
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/reset-password-email")
  public ResponseEntity<?> resetPasswordByEmail(
      @RequestBody ResetPasswordEmailRequest req, HttpServletRequest httpReq) {
    try {
      authService.resetPasswordByEmail(
          req.email(), req.code(), req.newPassword(), getClientIp(httpReq));
      return ResponseEntity.ok(Map.of("message", "密码已重置"));
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/reset-password-sms")
  public ResponseEntity<?> resetPasswordBySms(
      @RequestBody ResetPasswordSmsRequest req, HttpServletRequest httpReq) {
    try {
      authService.resetPasswordBySms(
          req.phone(), req.code(), req.newPassword(), getClientIp(httpReq));
      return ResponseEntity.ok(Map.of("message", "密码已重置"));
    } catch (RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @PostMapping("/set-password")
  public ResponseEntity<?> setPassword(
      @RequestBody SetPasswordRequest req, @AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
    }
    try {
      authService.setPassword(principal.getId(), req.password());
      return ResponseEntity.ok(Map.of("message", "密码已设置"));
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
