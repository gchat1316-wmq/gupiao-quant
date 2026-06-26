package com.quant.service.tdx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.ProsperityStrongProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 通达信 OAuth 2.0 + PKCE 客户端。
 *
 * 流程：
 *   1) startAuthorization() — 调 /register 拿 client_id（缓存到 token.json），生成 PKCE code_verifier+challenge，
 *      返回 authorize URL 给前端（用户需在浏览器打开 → 登录腾讯账号 → 授权）
 *   2) 回调 redirect_uri 时, 前端 GET /api/tdx/auth/callback?code=xxx&state=xxx
 *   3) handleCallback() — 调 /token 换 access_token + refresh_token，缓存到 token.json
 *   4) getValidAccessToken() — 每次用之前检查 expiry, 临近过期自动 refresh
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TdxOAuthClient {

    private final ProsperityStrongProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build();

    public record TdxToken(
            String clientId,
            String accessToken,
            String refreshToken,
            long expiresAtEpochMs
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CachedState(
            String clientId,
            String accessToken,
            String refreshToken,
            Long expiresAtEpochMs,
            /** 临时态: 等待 callback 的 code_verifier + state */
            String pendingCodeVerifier,
            String pendingState
    ) {}

    // ============================================================
    // 1. 启动授权：返回 authorize URL
    // ============================================================
    public synchronized StartResult startAuthorization() {
        return startAuthorization(props.getTdx().getRedirectUri());
    }

    public synchronized StartResult startAuthorization(String redirectUri) {
        CachedState state = readCache();
        if (state == null) state = new CachedState(null, null, null, null, null, null);
        // 1) 没有 clientId 就动态注册
        if (state.clientId() == null || state.clientId().isBlank()) {
            String clientId = registerClient(redirectUri);
            state = new CachedState(clientId, null, null, null, null, null);
            writeCache(state);
        }
        // 2) 生成 PKCE
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = sha256Base64Url(codeVerifier);
        String stateParam = generateState();
        state = new CachedState(state.clientId(), null, null, null, codeVerifier, stateParam);
        writeCache(state);
        // 3) 拼 authorize URL
        String url = props.getTdx().getAuthorizationEndpoint()
                + "?response_type=code"
                + "&client_id=" + enc(state.clientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc("mcp.read")
                + "&state=" + enc(stateParam)
                + "&code_challenge=" + enc(codeChallenge)
                + "&code_challenge_method=S256";
        return new StartResult(url, state.clientId(), redirectUri);
    }

    public record StartResult(String authorizeUrl, String clientId, String redirectUri) {}

    // ============================================================
    // 2. 处理回调：换 token
    // ============================================================
    public synchronized TokenResult handleCallback(String code, String stateParam) {
        CachedState state = readCache();
        if (state == null || state.pendingState() == null || state.pendingCodeVerifier() == null) {
            throw new IllegalStateException("无 pending 授权, 请先调用 /api/tdx/auth/start");
        }
        if (!state.pendingState().equals(stateParam)) {
            throw new IllegalStateException("state 不匹配, 可能是 CSRF 攻击或 session 过期");
        }
        // 调 /token
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", props.getTdx().getRedirectUri());
        form.put("client_id", state.clientId());
        form.put("code_verifier", state.pendingCodeVerifier());
        JsonNode resp = postForm(props.getTdx().getTokenEndpoint(), form);
        String access = resp.path("access_token").asText();
        String refresh = resp.path("refresh_token").asText(null);
        long expiresIn = resp.path("expires_in").asLong(3600L);
        long expiresAt = Instant.now().toEpochMilli() + expiresIn * 1000L;
        if (access.isBlank()) {
            throw new IllegalStateException("token 响应缺少 access_token: " + resp.toString());
        }
        CachedState newState = new CachedState(state.clientId(), access, refresh, expiresAt, null, null);
        writeCache(newState);
        log.info("TDX OAuth 授权成功, expiresIn={}s, hasRefresh={}", expiresIn, refresh != null);
        return new TokenResult(access, refresh, expiresAt);
    }

    public record TokenResult(String accessToken, String refreshToken, long expiresAtEpochMs) {}

    // ============================================================
    // 3. 获取有效 access_token（自动 refresh）
    // ============================================================
    public synchronized Optional<String> getValidAccessToken() {
        CachedState state = readCache();
        if (state == null || state.accessToken() == null) return Optional.empty();
        long now = Instant.now().toEpochMilli();
        if (state.expiresAtEpochMs() != null && state.expiresAtEpochMs() - now > 60_000L) {
            return Optional.of(state.accessToken());
        }
        // 即将过期 / 已过期 → refresh
        if (state.refreshToken() == null) return Optional.empty();
        try {
            log.info("TDX access_token 即将过期, 自动 refresh");
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", state.refreshToken());
            form.put("client_id", state.clientId());
            JsonNode resp = postForm(props.getTdx().getTokenEndpoint(), form);
            String access = resp.path("access_token").asText();
            String newRefresh = resp.path("refresh_token").asText(state.refreshToken());
            long expiresIn = resp.path("expires_in").asLong(3600L);
            long expiresAt = Instant.now().toEpochMilli() + expiresIn * 1000L;
            if (access.isBlank()) return Optional.empty();
            CachedState refreshed = new CachedState(state.clientId(), access, newRefresh, expiresAt, null, null);
            writeCache(refreshed);
            return Optional.of(access);
        } catch (Exception e) {
            log.warn("TDX refresh_token 失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ============================================================
    // 4. 状态查询
    // ============================================================
    public synchronized Status getStatus() {
        CachedState state = readCache();
        boolean hasToken = state != null && state.accessToken() != null;
        boolean hasPending = state != null && state.pendingState() != null;
        long expiresAt = state == null ? 0L : (state.expiresAtEpochMs() == null ? 0L : state.expiresAtEpochMs());
        return new Status(hasToken, hasPending, state == null ? null : state.clientId(),
                expiresAt, props.getTdx().getRedirectUri());
    }

    public record Status(boolean authorized, boolean pending, String clientId,
                         long expiresAtEpochMs, String redirectUri) {}

    // ============================================================
    // 内部实现
    // ============================================================
    private String registerClient() {
        return registerClient(props.getTdx().getRedirectUri());
    }

    private String registerClient(String redirectUri) {
        try {
            Map<String, Object> body = Map.of(
                    "redirect_uris", java.util.List.of(redirectUri),
                    "token_endpoint_auth_method", "none",
                    "grant_types", java.util.List.of("authorization_code", "refresh_token"),
                    "response_types", java.util.List.of("code"),
                    "client_name", props.getTdx().getClientName()
            );
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getTdx().getRegistrationEndpoint()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("register 失败: " + resp.statusCode() + " " + resp.body());
            }
            JsonNode node = objectMapper.readTree(resp.body());
            String clientId = node.path("client_id").asText();
            if (clientId.isBlank()) throw new IOException("register 响应缺 client_id: " + resp.body());
            log.info("TDX 动态注册成功, clientId={}", clientId);
            return clientId;
        } catch (Exception e) {
            throw new RuntimeException("TDX 动态注册失败: " + e.getMessage(), e);
        }
    }

    private JsonNode postForm(String url, Map<String, String> form) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (sb.length() > 0) sb.append("&");
                sb.append(enc(e.getKey())).append("=").append(enc(e.getValue()));
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("token 失败: " + resp.statusCode() + " " + resp.body());
            }
            return objectMapper.readTree(resp.body());
        } catch (Exception e) {
            throw new RuntimeException("TDX POST " + url + " 失败: " + e.getMessage(), e);
        }
    }

    private CachedState readCache() {
        try {
            Path p = Path.of(props.getTdx().getTokenCachePath());
            if (!Files.exists(p)) return null;
            return objectMapper.readValue(Files.readString(p, StandardCharsets.UTF_8), CachedState.class);
        } catch (Exception e) {
            log.warn("读 TDX token cache 失败: {}", e.getMessage());
            return null;
        }
    }

    private synchronized void writeCache(CachedState state) {
        try {
            Path p = Path.of(props.getTdx().getTokenCachePath());
            Files.createDirectories(p.getParent());
            Files.writeString(p, objectMapper.writeValueAsString(state), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写 TDX token cache 失败: {}", e.getMessage());
        }
    }

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateState() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Base64Url(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
