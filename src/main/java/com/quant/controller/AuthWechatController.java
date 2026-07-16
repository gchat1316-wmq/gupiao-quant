package com.quant.controller;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import com.quant.service.AuthService;
import com.quant.service.AuthService.AuthResult;
import com.quant.service.AuthService.UserDto;
import com.quant.service.wechat.WechatMpService;
import com.quant.service.wechat.WechatScanSession;
import com.quant.service.wechat.WechatScanSession.ScanStatus;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequiredArgsConstructor
public class AuthWechatController {

  private final AuthService authService;
  private final WechatMpService wechatMpService;

  /** 开放平台 - 网站应用扫码登录（用户在外部微信页面扫码授权后回调） */
  @Value("${app.wechat.app-id:}")
  private String wechatAppId;

  @Value("${app.wechat.app-secret:}")
  private String wechatAppSecret;

  @Value("${app.wechat.redirect-uri:}")
  private String wechatRedirectUri;

  /** 公众号（已认证服务号）扫码登录 */
  @Value("${app.wechat.mp.app-id:}")
  private String mpAppId;

  @Value("${app.wechat.mp.app-secret:}")
  private String mpAppSecret;

  /** 公众号服务器回调地址，本服务接收微信推送 event 的入口 */
  @Value("${app.wechat.mp.callback-url:}")
  private String mpCallbackUrl;

  /** 一站式：告诉前端当前可用的微信登录能力（OAuth / 公众号扫码 / 未配置） */
  @GetMapping("/api/auth/wechat/qr-info")
  public ResponseEntity<?> wechatQrInfo() {
    Map<String, Object> info = new HashMap<>();
    boolean mpReady =
        mpAppId != null && !mpAppId.isBlank() && mpAppSecret != null && !mpAppSecret.isBlank();
    boolean oauthReady =
        wechatAppId != null
            && !wechatAppId.isBlank()
            && wechatAppSecret != null
            && !wechatAppSecret.isBlank();
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
  @GetMapping("/api/auth/wechat/qr-url")
  public ResponseEntity<?> wechatQrUrl() {
    if (wechatAppId == null
        || wechatAppId.isBlank()
        || wechatAppSecret == null
        || wechatAppSecret.isBlank()) {
      return ResponseEntity.ok(
          Map.of(
              "authorizeUrl", "",
              "ready", false,
              "note", "请在环境变量中设置 WECHAT_APP_ID / WECHAT_APP_SECRET"));
    }
    String state = UUID.randomUUID().toString();
    String url =
        String.format(
            "https://open.weixin.qq.com/connect/qrconnect?appid=%s&redirect_uri=%s&response_type=code&scope=snsapi_login&state=%s#wechat_redirect",
            wechatAppId, wechatRedirectUri, state);
    return ResponseEntity.ok(
        Map.of(
            "authorizeUrl", url,
            "state", state,
            "ready", true));
  }

  /** 微信授权回调，用 code 换 token；返回 HTML 写入 localStorage 并跳回主页 */
  @GetMapping("/api/auth/wechat/callback")
  public ResponseEntity<?> wechatCallback(
      @RequestParam String code,
      @RequestParam(required = false) String state,
      HttpServletRequest httpReq) {
    if (wechatAppId == null
        || wechatAppId.isBlank()
        || wechatAppSecret == null
        || wechatAppSecret.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "微信登录未配置"));
    }
    String ip = getClientIp(httpReq);

    try {
      // 用 code 换 access_token
      String tokenUrl =
          String.format(
              "https://api.weixin.qq.com/sns/oauth2/access_token?appid=%s&secret=%s&code=%s&grant_type=authorization_code",
              wechatAppId, wechatAppSecret, code);

      Map<?, ?> tokenData =
          WebClient.builder()
              .build()
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
      String userInfoUrl =
          String.format(
              "https://api.weixin.qq.com/sns/userinfo?access_token=%s&openid=%s",
              accessToken, openid);
      Map<?, ?> wxUser =
          WebClient.builder()
              .build()
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

  /** 前端轮询入口：创建扫码会话，返回二维码图片地址与 sessionId */
  @GetMapping("/api/auth/wechat/mp/qr")
  public ResponseEntity<?> wechatMpQr() {
    if (mpAppId == null || mpAppId.isBlank()) {
      return ResponseEntity.ok(Map.of("ready", false, "note", "公众号登录未配置"));
    }
    try {
      WechatScanSession session = wechatMpService.createScanSession();
      return ResponseEntity.ok(
          Map.of(
              "ready", true,
              "sessionId", session.getSessionId(),
              "qrUrl", session.getQrUrl(),
              "expireSeconds", session.getExpireSeconds()));
    } catch (Exception e) {
      return ResponseEntity.badRequest()
          .body(Map.of("ready", false, "note", "创建二维码失败：" + e.getMessage()));
    }
  }

  /** 浏览器轮询：查询本次扫码会话的最新状态 */
  @GetMapping("/api/auth/wechat/mp/poll")
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
   * 公众号服务器回调入口（GET 同时支持连通性校验和事件推送 XML）。 query 里带 echostr 时是首次校验 server，回显 echostr 即可。 其它情况下
   * WechatMpService 会校验 signature 并消费 XML 推送。
   */
  @GetMapping(value = "/api/auth/wechat/mp/callback", produces = MediaType.TEXT_PLAIN_VALUE)
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
    String html =
        """
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
                """
            .formatted(
                jsonString(result.token()), jsonString(toUserJson(result)), jsonString(redirectTo));
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
    String html =
        """
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
                """
            .formatted(escapeHtml(message));
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
  }

  private static String jsonString(String s) {
    if (s == null) return "null";
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
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
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private String getClientIp(HttpServletRequest req) {
    String xff = req.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].trim();
    }
    return req.getRemoteAddr();
  }
}
