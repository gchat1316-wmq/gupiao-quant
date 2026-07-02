package com.quant.controller;

import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.UserPrincipal;
import com.quant.service.AuthService;
import com.quant.service.AuthService.AuthResult;
import com.quant.service.AuthService.UserDto;
import com.quant.service.EmailService;
import com.quant.service.SmsService;
import com.quant.service.wechat.WechatMpService;
import com.quant.service.wechat.WechatScanSession;
import com.quant.service.wechat.WechatScanSession.ScanStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final WechatMpService wechatMpService;
    private final SmsService smsService;
    private final EmailService emailService;

    @Value("${app.sms.code-expire-minutes:5}")
    private int codeExpireMinutes;

    @Value("${app.sms.cooldown-seconds:60}")
    private int cooldownSeconds;

    /**
     * 开放平台 - 网站应用扫码登录（用户在外部微信页面扫码授权后回调）
     */
    @Value("${app.wechat.app-id:}")
    private String wechatAppId;

    @Value("${app.wechat.app-secret:}")
    private String wechatAppSecret;

    @Value("${app.wechat.redirect-uri:}")
    private String wechatRedirectUri;

    /**
     * 公众号（已认证服务号）扫码登录
     */
    @Value("${app.wechat.mp.app-id:}")
    private String mpAppId;

    @Value("${app.wechat.mp.app-secret:}")
    private String mpAppSecret;

    /** 公众号服务器回调地址，本服务接收微信推送 event 的入口 */
    @Value("${app.wechat.mp.callback-url:}")
    private String mpCallbackUrl;

    public AuthController(AuthService authService,
                          UserRepository userRepository,
                          WechatMpService wechatMpService,
                          SmsService smsService,
                          EmailService emailService) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.wechatMpService = wechatMpService;
        this.smsService = smsService;
        this.emailService = emailService;
    }

    // ── 发送验证码 ──────────────────────────────────────

    public record SendCodeRequest(String phone) {}

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

    // ── 发送邮箱验证码 ──────────────────────────────────

    public record SendEmailCodeRequest(String email) {}

    @PostMapping("/send-email-code")
    public ResponseEntity<?> sendEmailCode(@RequestBody SendEmailCodeRequest req, HttpServletRequest httpReq) {
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

    // ── 邮箱 + 密码 注册 ──────────────────────────────────

    public record RegisterEmailRequest(String email, String password) {}

    @PostMapping("/register-email")
    public ResponseEntity<?> registerEmail(@RequestBody RegisterEmailRequest req, HttpServletRequest httpReq) {
        String ip = getClientIp(httpReq);
        try {
            AuthResult result = authService.registerWithEmail(req.email(), req.password(), ip);
            return ResponseEntity.ok(new AuthResponse(result.token(), "bearer", result.isNewUser(), result.toDto()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 邮箱验证码登录/注册 ───────────────────────────────

    public record VerifyEmailCodeRequest(String email, String code) {}

    @PostMapping("/verify-email-code")
    public ResponseEntity<?> verifyEmailCode(@RequestBody VerifyEmailCodeRequest req, HttpServletRequest httpReq) {
        String ip = getClientIp(httpReq);
        try {
            AuthResult result = authService.verifyEmailCode(req.email(), req.code(), ip);
            return ResponseEntity.ok(new AuthResponse(result.token(), "bearer", result.isNewUser(), result.toDto()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 重置密码：邮箱验证码 ───────────────────────────────

    public record ResetPasswordEmailRequest(String email, String code, String newPassword) {}

    @PostMapping("/reset-password-email")
    public ResponseEntity<?> resetPasswordByEmail(@RequestBody ResetPasswordEmailRequest req,
                                                  HttpServletRequest httpReq) {
        try {
            authService.resetPasswordByEmail(req.email(), req.code(), req.newPassword(), getClientIp(httpReq));
            return ResponseEntity.ok(Map.of("message", "密码已重置"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 重置密码：短信验证码 ───────────────────────────────

    public record ResetPasswordSmsRequest(String phone, String code, String newPassword) {}

    @PostMapping("/reset-password-sms")
    public ResponseEntity<?> resetPasswordBySms(@RequestBody ResetPasswordSmsRequest req,
                                                HttpServletRequest httpReq) {
        try {
            authService.resetPasswordBySms(req.phone(), req.code(), req.newPassword(), getClientIp(httpReq));
            return ResponseEntity.ok(Map.of("message", "密码已重置"));
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
                        u.getId(), u.getPhone(), u.getEmail(), u.getOpenid(), u.getUsername(), u.getRole().name(),
                        u.getDisabled(), u.getAvatarUrl(), u.getNotifyWechat(), u.getNotifySms(), u.getNotifyPhone())))
                .orElse(ResponseEntity.status(404).body(Map.of("error", "用户不存在")));
    }

    /** 更新个人资料 */
    public record ProfileUpdateRequest(
            String phone,
            String phoneCode,
            String avatarUrl,
            Boolean notifyWechat,
            Boolean notifySms,
            Boolean notifyPhone
    ) {}

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ProfileUpdateRequest req,
            HttpServletRequest httpReq) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        try {
            UserDto updated = authService.updateProfile(
                    principal.getId(),
                    req.phone(), req.phoneCode(), req.avatarUrl(),
                    req.notifyWechat(), req.notifySms(), req.notifyPhone(),
                    getClientIp(httpReq));
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── 微信登录 ────────────────────────────────────────

    /** 一站式：告诉前端当前可用的微信登录能力（OAuth / 公众号扫码 / 未配置） */
    @GetMapping("/wechat/qr-info")
    public ResponseEntity<?> wechatQrInfo() {
        Map<String, Object> info = new HashMap<>();
        boolean mpReady = mpAppId != null && !mpAppId.isBlank()
                && mpAppSecret != null && !mpAppSecret.isBlank();
        boolean oauthReady = wechatAppId != null && !wechatAppId.isBlank()
                && wechatAppSecret != null && !wechatAppSecret.isBlank();
        info.put("mpReady", mpReady);
        info.put("oauthReady", oauthReady);
        if (mpReady) {
            info.put("mode", "mp");
            info.put("description", "用微信扫一扫二维码即可登录");
        } else if (oauthReady) {
            info.put("mode", "oauth");
            info.put("description", "用微信扫码授权后登录");
        } else {
            info.put("mode", "none");
            info.put("description", "管理员尚未配置微信登录参数");
        }
        return ResponseEntity.ok(info);
    }

    /** 获取微信登录二维码跳转 URL（开放平台 OAuth） */
    @GetMapping("/wechat/qr-url")
    public ResponseEntity<?> wechatQrUrl() {
        if (wechatAppId == null || wechatAppId.isBlank()
                || wechatAppSecret == null || wechatAppSecret.isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "authorizeUrl", "",
                    "ready", false,
                    "note", "请在环境变量中设置 WECHAT_APP_ID / WECHAT_APP_SECRET"));
        }
        String state = UUID.randomUUID().toString();
        String url = String.format(
            "https://open.weixin.qq.com/connect/qrconnect?appid=%s&redirect_uri=%s&response_type=code&scope=snsapi_login&state=%s#wechat_redirect",
            wechatAppId, wechatRedirectUri, state
        );
        return ResponseEntity.ok(Map.of(
                "authorizeUrl", url,
                "state", state,
                "ready", true));
    }

    /** 微信授权回调，用 code 换 token；返回 HTML 写入 localStorage 并跳回主页 */
    @GetMapping("/wechat/callback")
    public ResponseEntity<?> wechatCallback(
            @RequestParam String code,
            @RequestParam(required = false) String state,
            HttpServletRequest httpReq) {
        if (wechatAppId == null || wechatAppId.isBlank()
                || wechatAppSecret == null || wechatAppSecret.isBlank()) {
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
                return callbackErrorPage("微信授权失败：" + tokenData);
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
            return callbackSuccessPage(result, "/gp/");

        } catch (Exception e) {
            return callbackErrorPage("微信登录失败：" + e.getMessage());
        }
    }

    // ── 公众号「带参数二维码」扫码登录（业内标准体验）──────────

    /** 前端轮询入口：创建扫码会话，返回二维码图片地址与 sessionId */
    @GetMapping("/wechat/mp/qr")
    public ResponseEntity<?> wechatMpQr() {
        if (mpAppId == null || mpAppId.isBlank()) {
            return ResponseEntity.ok(Map.of("ready", false, "note", "公众号登录未配置"));
        }
        try {
            WechatScanSession session = wechatMpService.createScanSession();
            return ResponseEntity.ok(Map.of(
                    "ready", true,
                    "sessionId", session.getSessionId(),
                    "qrUrl", session.getQrUrl(),
                    "expireSeconds", session.getExpireSeconds()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ready", false, "note", "创建二维码失败：" + e.getMessage()));
        }
    }

    /** 浏览器轮询：查询本次扫码会话的最新状态 */
    @GetMapping("/wechat/mp/poll")
    public ResponseEntity<?> wechatMpPoll(@RequestParam("sessionId") String sessionId) {
        WechatScanSession session = wechatMpService.get(sessionId);
        if (session == null) {
            return ResponseEntity.ok(Map.of("status", "EXPIRED"));
        }
        ScanStatus status = session.getStatus();
        Map<String, Object> body = new HashMap<>();
        body.put("status", status.name());
        if (status == ScanStatus.CONFIRMED || status == ScanStatus.SCANNED) {
            // SCANNED 仅做提示；CONFIRMED 表示用户在手机上确认了授权，可以查 token
            body.put("openid", session.getOpenid());
        }
        if (status == ScanStatus.LOGGED_IN) {
            body.put("accessToken", session.getAccessToken());
            body.put("user", session.getUserDto());
        }
        return ResponseEntity.ok(body);
    }

    /**
     * 公众号服务器回调入口（GET 同时支持连通性校验和事件推送 XML）。
     * query 里带 echostr 时是首次校验 server，回显 echostr 即可。
     * 其它情况下 WechatMpService 会校验 signature 并消费 XML 推送。
     */
    @GetMapping(value = "/wechat/mp/callback",
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> wechatMpCallback(
            @RequestParam(value = "signature", required = false) String signature,
            @RequestParam(value = "timestamp", required = false) String timestamp,
            @RequestParam(value = "nonce", required = false) String nonce,
            @RequestParam(value = "echostr", required = false) String echostr,
            @RequestBody(required = false) String xml) {
        if (echostr != null && !echostr.isBlank()) {
            return ResponseEntity.ok(echostr);
        }
        try {
            wechatMpService.handleEvent(xml, signature, timestamp, nonce);
        } catch (Exception ignore) {
            // 公众号要求 5s 内 200，否则重试；出错也 200 回 "success"
        }
        return ResponseEntity.ok("success");
    }

    /** 构造扫码成功后的 HTML：把 token + user 写入 localStorage，通知主窗口或跳回主页 */
    private ResponseEntity<String> callbackSuccessPage(AuthResult result, String redirectTo) {
        String html = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>登录成功</title>
                  <style>
                    body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
                           background: #f7f8fa; color: #333; margin: 0; }
                    .wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; }
                    .card { background: #fff; padding: 32px 48px; border-radius: 12px;
                            box-shadow: 0 8px 32px rgba(0,0,0,0.08); text-align: center; min-width: 280px; }
                    .ok { font-size: 40px; color: #07c160; margin-bottom: 8px; }
                    h1 { font-size: 18px; margin: 0 0 6px; }
                    p  { font-size: 13px; color: #888; margin: 0; }
                  </style>
                </head>
                <body>
                  <div class="wrap">
                    <div class="card">
                      <div class="ok">&#10003;</div>
                      <h1>登录成功</h1>
                      <p>即将跳转…</p>
                    </div>
                  </div>
                  <script>
                    (function () {
                      try {
                        localStorage.setItem('gp_auth_token', %s);
                        localStorage.setItem('gp_auth_user', %s);
                      } catch (e) {}
                      if (window.opener && !window.opener.closed) {
                        try { window.opener.postMessage({ type: 'gp-auth-success' }, '*'); } catch (e) {}
                        window.close();
                        return;
                      }
                      setTimeout(function () { window.location.replace(%s); }, 300);
                    })();
                  </script>
                </body>
                </html>
                """.formatted(
                        jsonString(result.token()),
                        jsonString(toUserJson(result)),
                        jsonString(redirectTo));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private String toUserJson(AuthResult result) {
        UserDto u = result.toDto();
        Map<String, Object> m = new HashMap<>();
        m.put("id", u.id());
        m.put("phone", u.phone());
        m.put("openid", u.openid());
        m.put("username", u.username());
        m.put("role", u.role());
        m.put("disabled", u.disabled());
        m.put("avatarUrl", u.avatarUrl());
        m.put("notifyWechat", u.notifyWechat());
        m.put("notifySms", u.notifySms());
        m.put("notifyPhone", u.notifyPhone());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ResponseEntity<String> callbackErrorPage(String message) {
        String html = """
                <!doctype html>
                <html lang="zh-CN">
                <head><meta charset="utf-8"><title>登录失败</title>
                <style>body{font-family:sans-serif;background:#f7f8fa;color:#333;margin:0}
                .wrap{min-height:100vh;display:flex;align-items:center;justify-content:center}
                .card{background:#fff;padding:32px 48px;border-radius:12px;
                      box-shadow:0 8px 32px rgba(0,0,0,0.08);text-align:center;min-width:280px}
                .err{font-size:40px;color:#e53;margin-bottom:8px}
                h1{font-size:18px;margin:0 0 6px}
                p{font-size:13px;color:#888;margin:0 0 12px}
                a{color:#07c160;text-decoration:none;font-size:13px}</style>
                </head>
                <body><div class="wrap"><div class="card">
                  <div class="err">&#10005;</div>
                  <h1>登录失败</h1>
                  <p>%s</p>
                  <a href="javascript:window.close()">关闭页面</a>
                </div></div></body></html>
                """.formatted(escapeHtml(message));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
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
            UserDto updated = authService.updateProfile(id, null, null, null,
                    req.get("notifyWechat"), req.get("notifySms"), req.get("notifyPhone"), null);
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
