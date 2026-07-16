package com.quant.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.repository.UserRepository;
import com.quant.security.UserPrincipal;
import com.quant.service.AuthService;
import com.quant.service.AuthService.UserDto;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/auth")
@Slf4j
@RequiredArgsConstructor
public class AuthProfileController {

  private final AuthService authService;
  private final UserRepository userRepository;

  /** 更新个人资料 */
  public record ProfileUpdateRequest(
      String phone,
      String phoneCode,
      String avatarUrl,
      Boolean notifyWechat,
      Boolean notifySms,
      Boolean notifyPhone) {}

  @GetMapping("/me")
  public ResponseEntity<?> me(@AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Map.of("error", "未登录"));
    }
    return userRepository
        .findById(principal.getId())
        .<ResponseEntity<?>>map(
            u ->
                ResponseEntity.ok(
                    new UserDto(
                        u.getId(),
                        u.getPhone(),
                        u.getEmail(),
                        u.getOpenid(),
                        u.getUsername(),
                        u.getRole().name(),
                        u.getDisabled(),
                        u.getAvatarUrl(),
                        u.getNotifyWechat(),
                        u.getNotifySms(),
                        u.getNotifyPhone())))
        .orElse(ResponseEntity.status(404).body(Map.of("error", "用户不存在")));
  }

  @PutMapping("/profile")
  public ResponseEntity<?> updateProfile(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestBody ProfileUpdateRequest req,
      HttpServletRequest httpReq) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Map.of("error", "未登录"));
    }
    try {
      UserDto updated =
          authService.updateProfile(
              principal.getId(),
              req.phone(),
              req.phoneCode(),
              req.avatarUrl(),
              req.notifyWechat(),
              req.notifySms(),
              req.notifyPhone(),
              getClientIp(httpReq));
      return ResponseEntity.ok(updated);
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
