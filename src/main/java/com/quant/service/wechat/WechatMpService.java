package com.quant.service.wechat;

import com.quant.service.AuthService;
import com.quant.service.AuthService.AuthResult;
import com.quant.service.wechat.WechatScanSession.ScanStatus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信公众号（已认证服务号）"带参数二维码"扫码登录：
 * 1. createScanSession 调 cgi-bin/qrcode/create 生成 ticket，前端拿到 showqrcode URL 渲染二维码；
 * 2. 公众号后台配置的回调 URL 把事件 SCAN/subscribe 推给我们，从 EventKey 解出 sessionId，
 *    绑定 FromUserName（openid），调用 AuthService.loginWithWechat 签发 JWT；
 * 3. 前端轮询 /api/auth/wechat/mp/poll 拿到 accessToken，写入 localStorage 并跳回主页。
 *
 * 注：是否发送"二次确认"的客服消息由业务侧决定；当前实现为"扫码即授权"。
 */
@Slf4j
@Service
public class WechatMpService {

    @Value("${app.wechat.mp.app-id:}")
    private String appId;

    @Value("${app.wechat.mp.app-secret:}")
    private String appSecret;

    /** 公众号后台"消息校验 Token"，不配则跳过签名校验（仅开发用） */
    @Value("${app.wechat.mp.callback-token:}")
    private String callbackToken;

    private final AuthService authService;
    private final WebClient webClient;
    private final ConcurrentMap<String, WechatScanSession> sessions = new ConcurrentHashMap<>();
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    // 用负向先行断言排除外层 <xml>...</xml> 包裹，避免 lazy 量词把整个 body 吃掉。
    // 例：<xml><ToUserName>...</ToUserName></xml> 之前的 regex 会优先匹配 <xml>...</xml>，
    // 导致内层字段全部丢失，handleEvent 永远拿到 null 然后静默 return。
    private static final Pattern XML_FIELD = Pattern.compile("<(?!xml\\b)([A-Za-z0-9_]+)>([\\s\\S]*?)</\\1>");

    /** 生产构造器：注入 AuthService，自动构造默认 WebClient。 */
    @Autowired
    public WechatMpService(AuthService authService) {
        this(authService, WebClient.builder().build());
    }

    /** 测试构造器：允许注入 mock WebClient，便于单元测试。 */
    WechatMpService(AuthService authService, WebClient webClient) {
        this.authService = authService;
        this.webClient = webClient;
    }

    @PostConstruct
    void warnIfMissing() {
        if (isBlank(appId) || isBlank(appSecret)) {
            log.info("WechatMpService 未配置 app.wechat.mp.app-id/app-secret，公众号扫码登录入口不可用");
        }
    }

    public boolean isReady() {
        return !isBlank(appId) && !isBlank(appSecret);
    }

    /** 创建一个新的扫码会话，分配 UUID 作为 scene_str，调 qrcode/create 拿到 showqrcode URL */
    public WechatScanSession createScanSession() {
        if (!isReady()) {
            throw new RuntimeException("公众号 AppID/AppSecret 未配置");
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        String token = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/qrcode/create?access_token=" + token;
        Map<String, Object> body = Map.of(
                "expire_seconds", 300,
                "action_name", "QR_STR_SCENE",
                "action_info", Map.of("scene", Map.of("scene_str", sessionId))
        );
        Map<?, ?> res = webClient.post()
                .uri(url)
                .header("Content-Type", "application/json; charset=utf-8")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(10));
        if (res == null || res.containsKey("errcode")) {
            throw new RuntimeException("生成二维码失败：" + res);
        }
        String ticket = (String) res.get("ticket");
        if (ticket == null || ticket.isBlank()) {
            throw new RuntimeException("返回 ticket 为空：" + res);
        }
        String showUrl = "https://mp.weixin.qq.com/cgi-bin/showqrcode?ticket="
                + URLEncoder.encode(ticket, StandardCharsets.UTF_8);

        WechatScanSession session = new WechatScanSession();
        session.setSessionId(sessionId);
        session.setQrUrl(showUrl);
        session.setExpireSeconds(300);
        session.setCreatedAt(Instant.now());
        sessions.put(sessionId, session);
        cleanupExpired();
        return session;
    }

    public WechatScanSession get(String sessionId) {
        if (sessionId == null) return null;
        WechatScanSession s = sessions.get(sessionId);
        if (s == null) return null;
        if (s.isExpired()) {
            sessions.remove(sessionId, s);
            return null;
        }
        return s;
    }

    /**
     * 公众号事件推送入口（GET callback 在 AuthController 已经放过 echostr）。
     * 这里仅处理业务事件：subscribe+qrscene_/SCAN。
     */
    public void handleEvent(String xml, String signature, String timestamp, String nonce) {
        if (xml == null || xml.isBlank()) return;
        if (!isBlank(callbackToken)) {
            if (!verifySignature(signature, timestamp, nonce, callbackToken)) {
                log.warn("公众号回调签名校验失败: signature={}, ts={}, nonce={}", signature, timestamp, nonce);
                return;
            }
        }
        Map<String, String> fields = parseXml(xml);
        String event = fields.get("Event");
        String eventKey = fields.get("EventKey");
        String fromUser = fields.get("FromUserName");
        if (event == null || eventKey == null || fromUser == null) {
            log.warn("公众号事件字段缺失: event={}, key={}, from={}", event, eventKey, fromUser);
            return;
        }

        String sessionId;
        if ("subscribe".equalsIgnoreCase(event) && eventKey.startsWith("qrscene_")) {
            // 未关注用户扫码后关注公众号，eventKey = "qrscene_<scene_str>"
            sessionId = eventKey.substring("qrscene_".length());
        } else if ("SCAN".equalsIgnoreCase(event)) {
            // 已关注用户扫码，eventKey 直接是 scene_str
            sessionId = eventKey;
        } else if ("unsubscribe".equalsIgnoreCase(event)) {
            return;
        } else {
            log.info("忽略公众号事件 type={}", event);
            return;
        }

        WechatScanSession session = sessions.get(sessionId);
        if (session == null) {
            log.info("扫码会话 {} 不存在或已过期（可能用户没等到 backend 就已扫码）", sessionId);
            return;
        }
        if (session.getStatus() == ScanStatus.LOGGED_IN) {
            return; // 已处理，防重入
        }
        session.setOpenid(fromUser);
        session.setStatus(ScanStatus.CONFIRMED);
        try {
            AuthResult result = authService.loginWithWechat(fromUser, null, null, "wechat-mp");
            session.setAccessToken(result.token());
            session.setUserDto(result.toDto());
            session.setStatus(ScanStatus.LOGGED_IN);
            log.info("公众号扫码登录成功: sessionId={}, openid={}", sessionId, fromUser);
        } catch (Exception e) {
            log.error("扫码后登录失败", e);
            session.setStatus(ScanStatus.EXPIRED);
        }
    }

    // ── access_token 缓存（7200s，提前 300s 刷新） ──────────────────────────

    private String getAccessToken() {
        CachedToken cached = cachedToken.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt.minusSeconds(300))) {
            return cached.token();
        }
        String url = String.format(
                "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s",
                appId, appSecret);
        Map<?, ?> res = webClient.get().uri(url)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(10));
        if (res == null || !res.containsKey("access_token")) {
            throw new RuntimeException("获取 access_token 失败：" + res);
        }
        String token = (String) res.get("access_token");
        long expiresIn = ((Number) res.get("expires_in")).longValue();
        cachedToken.set(new CachedToken(token, Instant.now().plusSeconds(expiresIn)));
        return token;
    }

    private void cleanupExpired() {
        sessions.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private static boolean verifySignature(String signature, String timestamp, String nonce, String token) {
        if (signature == null || timestamp == null || nonce == null) return false;
        List<String> list = new ArrayList<>(3);
        list.add(token);
        list.add(timestamp);
        list.add(nonce);
        Collections.sort(list);
        String joined = String.join("", list);
        return sha1Hex(joined).equalsIgnoreCase(signature);
    }

    private static Map<String, String> parseXml(String xml) {
        Map<String, String> result = new HashMap<>();
        Matcher m = XML_FIELD.matcher(xml);
        while (m.find()) {
            String value = m.group(2);
            // 微信 XML 字段值常被 CDATA 包住，例如 <Event><![CDATA[subscribe]]></Event>
            // 不剥壳的话 equalsIgnoreCase("subscribe") 等比较会全部落空。
            if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) {
                value = value.substring("<![CDATA[".length(), value.length() - "]]>".length());
            }
            result.put(m.group(1), value);
        }
        return result;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 不可用", e);
        }
    }

    private record CachedToken(String token, Instant expiresAt) {}
}
