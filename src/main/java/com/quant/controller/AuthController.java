package com.quant.controller;

import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.UserPrincipal;
import com.quant.service.AuthService;
import com.quant.service.AuthService.AuthResult;
import com.quant.service.AuthService.UserDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    @Value("${app.sms.code-expire-minutes:5}")
    private int codeExpireMinutes;

    @Value("${app.sms.cooldown-seconds:60}")
    private int cooldownSeconds;

    @Value("${app.wechat.app-id:}")
    private String wechatAppId;

    @Value("${app.wechat.app-secret:}")
    private String wechatAppSecret;

    @Value("${app.wechat.redirect-uri:}")
    private String wechatRedirectUri;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    // ── 发送验证码 ──────────────────────────────────────

    public record SendCodeRequest(String phone) {}

    @PostMapping("/send-code")
    public ResponseEntity<?> sendCode(@RequestBody SendCodeRequest req, HttpServletRequest httpReq) {
        String ip = getClientIp(httpReq);
        try {
            authService.sendCode(req.phone(), ip);
            return ResponseEntity.ok(Map.of("message", "验证码已发送"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 验证码登录/注册 ──────────────────────────────────

    public record VerifyCodeRequest(String phone, String code) {}

    public record AuthResponse(String accessToken, String tokenType, boolean isNewUser, UserDto user) {}

    @PostMapping("/verify-code")
    public ResponseEntity<?> verifyCode(@RequestBody VerifyCodeRequest req, HttpServletRequest httpReq) {
        String ip = getClientIp(httpReq);
        try {
            AuthResult result = authService.verifyCode(req.phone(), req.code(), ip);
            return ResponseEntity.ok(new AuthResponse(result.token(), "bearer", result.isNewUser(), result.toDto()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 密码登录 ────────────────────────────────────────

    public record LoginRequest(String phone, String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req, HttpServletRequest httpReq) {
        String ip = getClientIp(httpReq);
        try {
            // 支持 phone 或 username 登录
            String identifier = req.phone() != null ? req.phone() : req.username();
            AuthResult result = authService.loginWithPassword(identifier, req.password(), ip);
            return ResponseEntity.ok(new AuthResponse(result.token(), "bearer", result.isNewUser(), result.toDto()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 设置密码（登录后） ───────────────────────────────

    public record SetPasswordRequest(String password) {}

    @PostMapping("/set-password")
    public ResponseEntity<?> setPassword(@RequestBody SetPasswordRequest req,
                                         @AuthenticationPrincipal UserPrincipal principal) {
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

    // ── 当前用户信息 ────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        return userRepository.findById(principal.getId())
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(new UserDto(
                        u.getId(), u.getPhone(), u.getOpenid(), u.getUsername(), u.getRole().name(),
                        u.getDisabled(), u.getAvatarUrl(), u.getNotifyWechat(), u.getNotifySms(), u.getNotifyPhone())))
                .orElse(ResponseEntity.status(404).body(Map.of("error", "用户不存在")));
    }

    /** 更新个人资料 */
    public record ProfileUpdateRequest(
            String phone,
            String avatarUrl,
            Boolean notifyWechat,
            Boolean notifySms,
            Boolean notifyPhone
    ) {}

    @PutMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ProfileUpdateRequest req) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        try {
            UserDto updated = authService.updateProfile(
                    principal.getId(),
                    req.phone(), req.avatarUrl(),
                    req.notifyWechat(), req.notifySms(), req.notifyPhone());
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 微信登录 ────────────────────────────────────────

    /** 获取微信登录二维码跳转 URL */
    @GetMapping("/wechat/qr-url")
    public ResponseEntity<?> wechatQrUrl() {
        if (wechatAppId == null || wechatAppId.isBlank()) {
            return ResponseEntity.ok(Map.of("authorizeUrl", "", "mock", true,
                    "note", "请在配置中填写 app.wechat.app-id"));
        }
        String state = UUID.randomUUID().toString();
        String url = String.format(
            "https://open.weixin.qq.com/connect/qrconnect?appid=%s&redirect_uri=%s&response_type=code&scope=snsapi_login&state=%s#wechat_redirect",
            wechatAppId, wechatRedirectUri, state
        );
        return ResponseEntity.ok(Map.of("authorizeUrl", url, "state", state, "mock", false));
    }

    /** 微信授权回调，用 code 换 token */
    @GetMapping("/wechat/callback")
    public ResponseEntity<?> wechatCallback(
            @RequestParam String code,
            @RequestParam(required = false) String state,
            HttpServletRequest httpReq) {
        if (wechatAppId == null || wechatAppId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "微信登录未配置"));
        }
        String ip = getClientIp(httpReq);

        try {
            // 用 code 换 access_token
            String tokenUrl = String.format(
                "https://api.weixin.qq.com/sns/oauth2/access_token?appid=%s&secret=%s&code=%s&grant_type=authorization_code",
                wechatAppId, wechatAppSecret, code
            );

            Map<?, ?> tokenData = WebClient.builder().build()
                    .get()
                    .uri(tokenUrl)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(10));

            if (tokenData == null || tokenData.containsKey("errcode")) {
                return ResponseEntity.badRequest().body(Map.of("error", "微信授权失败", "detail", tokenData));
            }

            String accessToken = (String) tokenData.get("access_token");
            String openid = (String) tokenData.get("openid");
            String unionid = (String) tokenData.get("unionid");

            // 获取用户信息
            String userInfoUrl = String.format(
                "https://api.weixin.qq.com/sns/userinfo?access_token=%s&openid=%s",
                accessToken, openid
            );
            Map<?, ?> wxUser = WebClient.builder().build()
                    .get()
                    .uri(userInfoUrl)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(10));

            String nickname = wxUser != null ? (String) wxUser.get("nickname") : null;

            AuthResult result = authService.loginWithWechat(openid, unionid, nickname, ip);
            return ResponseEntity.ok(new AuthResponse(result.token(), "bearer", result.isNewUser(), result.toDto()));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "微信登录失败: " + e.getMessage()));
        }
    }

    // ── 登录码登录 ──────────────────────────────────────

    /** 用登录码注册/登录（公开接口） */
    @PostMapping("/login-code")
    public ResponseEntity<?> loginWithCode(@RequestBody Map<String, String> req, HttpServletRequest httpReq) {
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

    // ── ADMIN：生成登录码 ───────────────────────────────

    /** ADMIN 生成登录码，可指定角色和有效期 */
    @PostMapping("/admin/login-code")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> generateLoginCode(
            @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal UserPrincipal principal) {
        try {
            String roleStr = (String) req.get("role");
            User.Role role = User.Role.valueOf(roleStr.toUpperCase());
            int expireDays = req.containsKey("expireDays") ? ((Number) req.get("expireDays")).intValue() : 7;
            String code = authService.generateLoginCode(principal.getId(), role, expireDays);
            return ResponseEntity.ok(Map.of("code", code, "role", role.name(), "expireDays", expireDays));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── ADMIN：用户管理 ─────────────────────────────────

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
            UserDto updated = authService.updateUserRole(principal.getId(), id, User.Role.valueOf(roleStr));
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
            authService.toggleUserDisabled(principal.getId(), id, Boolean.TRUE.equals(req.get("disabled")));
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
            UserDto updated = authService.updateProfile(id, null, null,
                    req.get("notifyWechat"), req.get("notifySms"), req.get("notifyPhone"));
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 工具方法 ────────────────────────────────────────

    private String getClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
