package com.quant.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

import com.quant.service.tdx.TdxMcpClient;
import com.quant.service.tdx.TdxOAuthClient;

import lombok.RequiredArgsConstructor;

/**
 * 通达信 MCP OAuth 授权端点。
 *
 * <p>流程: 1. 前端调用 GET /api/tdx/auth/status — 检查是否已授权 2. 未授权时调用 GET /api/tdx/auth/start — 返回
 * authorize URL, 后端生成 PKCE+state 写入 cache 3. 前端引导用户在浏览器打开 authorize URL, 用户登录腾讯账号并授权 4. TDX 回调 GET
 * /api/tdx/auth/callback?code=xxx&state=xxx 5. 后端用 code + code_verifier 调 /token 换 access_token +
 * refresh_token, 缓存到 token.json 6. 后续调 TDX MCP 工具 (wenda_report_query 等) 会自动用 cache 里的 access_token
 */
@RestController
@RequestMapping("/api/tdx/auth")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class TdxAuthController {

  private final TdxOAuthClient oauthClient;
  private final TdxMcpClient mcpClient;

  /** 检查授权状态 */
  @GetMapping("/status")
  public Map<String, Object> status() {
    TdxOAuthClient.Status s = oauthClient.getStatus();
    Map<String, Object> result = new HashMap<>();
    result.put("authorized", s.authorized());
    result.put("pending", s.pending());
    result.put("clientId", s.clientId());
    result.put("redirectUri", s.redirectUri());
    result.put("expiresAtEpochMs", s.expiresAtEpochMs());
    result.put("mcpUsable", mcpClient.isAuthorized());
    return result;
  }

  /** 启动授权：返回 authorize URL */
  @GetMapping("/start")
  public Map<String, Object> start(jakarta.servlet.http.HttpServletRequest request) {
    // 自动用当前请求的 host 拼 redirect_uri（避免 8080/8090 切换麻烦）
    String currentOrigin =
        request.getScheme()
            + "://"
            + request.getServerName()
            + (request.getServerPort() == 80 || request.getServerPort() == 443
                ? ""
                : ":" + request.getServerPort());
    String currentRedirect = currentOrigin + request.getContextPath() + "/api/tdx/auth/callback";
    TdxOAuthClient.StartResult r = oauthClient.startAuthorization(currentRedirect);
    Map<String, Object> result = new HashMap<>();
    result.put("authorizeUrl", r.authorizeUrl());
    result.put("clientId", r.clientId());
    result.put("redirectUri", r.redirectUri());
    result.put("currentOrigin", currentOrigin);
    result.put("message", "请在浏览器中打开 authorizeUrl 完成腾讯账号登录与授权, 授权完成后会自动回调到 redirectUri");
    return result;
  }

  /** TDX 回调：换 access_token */
  @GetMapping("/callback")
  public Map<String, Object> callback(
      @RequestParam("code") String code, @RequestParam("state") String state) {
    try {
      TdxOAuthClient.TokenResult r = oauthClient.handleCallback(code, state);
      Map<String, Object> result = new HashMap<>();
      result.put("ok", true);
      result.put("expiresAtEpochMs", r.expiresAtEpochMs());
      result.put("hasRefreshToken", r.refreshToken() != null);
      result.put("message", "TDX 授权成功, 后续 wenda_report_query 等 MCP 工具可直接使用");
      return result;
    } catch (Exception e) {
      Map<String, Object> result = new HashMap<>();
      result.put("ok", false);
      result.put("error", e.getMessage());
      return result;
    }
  }

  /** 清空 token (登出) */
  @GetMapping("/logout")
  public Map<String, Object> logout() {
    // 直接调 oauthClient 清空: 删 token.json 即可
    try {
      java.nio.file.Files.deleteIfExists(
          java.nio.file.Path.of(
              System.getProperty("user.home")
                  + "/.workbuddy/connectors-marketplace/connectors/tdx-connector/token.json"));
    } catch (Exception e) {
      // ignore
    }
    Map<String, Object> result = new HashMap<>();
    result.put("ok", true);
    return result;
  }
}
