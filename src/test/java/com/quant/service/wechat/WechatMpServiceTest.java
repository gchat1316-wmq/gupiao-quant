package com.quant.service.wechat;

import com.quant.entity.User;
import com.quant.service.AuthService;
import com.quant.service.AuthService.AuthResult;
import com.quant.service.wechat.WechatScanSession.ScanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * WechatMpService 单元测试。
 * 关键：WebClient 链式调用的泛型 Mockito 无法捕获，全部用 raw type + @SuppressWarnings 绕过。
 */
@DisplayName("WechatMpService")
@SuppressWarnings({"unchecked", "rawtypes"})
class WechatMpServiceTest {

    private AuthService authService;
    private WebClient webClient;
    private WebClient.RequestBodyUriSpec postSpec;
    private WebClient.RequestBodySpec bodySpec;
    private WebClient.ResponseSpec responseSpec;
    private WebClient.RequestHeadersUriSpec getSpec;

    private WechatMpService service;

    @BeforeEach
    void setUp() throws Exception {
        authService = mock(AuthService.class);
        webClient = mock(WebClient.class);
        postSpec = mock(WebClient.RequestBodyUriSpec.class);
        bodySpec = mock(WebClient.RequestBodySpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);
        getSpec = mock(WebClient.RequestHeadersUriSpec.class);

        Constructor<WechatMpService> ctor = WechatMpService.class
                .getDeclaredConstructor(AuthService.class, WebClient.class);
        ctor.setAccessible(true);
        service = ctor.newInstance(authService, webClient);

        ReflectionTestUtils.setField(service, "appId", "wx-test-app-id");
        ReflectionTestUtils.setField(service, "appSecret", "wx-test-app-secret");
        ReflectionTestUtils.setField(service, "callbackToken", "");
    }

    /** 让 access_token 和 qrcode/create 都能从 responseSpec.bodyToMono(Map.class) 拿到值 */
    private void stubAccessTokenAndPost(Map<String, Object> postPayload) {
        // access_token 走 get 链
        when(webClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(getSpec);
        when(getSpec.retrieve()).thenReturn(responseSpec);
        // qrcode/create 走 post 链
        when(webClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        doReturn(bodySpec).when(bodySpec).bodyValue(any());
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        // bodyToMono 按调用顺序返回：第一次 access_token，第二次 qrcode/create
        when(responseSpec.bodyToMono(Map.class))
                .thenReturn(Mono.just(Map.of("access_token", "tok-xyz", "expires_in", 7200)))
                .thenReturn(Mono.just(postPayload));
    }

    private void stubAccessTokenOnly() {
        when(webClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(getSpec);
        when(getSpec.retrieve()).thenReturn(responseSpec);
        when(webClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        doReturn(bodySpec).when(bodySpec).bodyValue(any());
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class))
                .thenReturn(Mono.just(Map.of("access_token", "tok-xyz", "expires_in", 7200)));
    }

    // ── isReady / createScanSession 边界 ─────────────────────────

    @Test
    @DisplayName("isReady: 未配置凭据 → false")
    void isReadyReturnsFalseWhenMissing() {
        ReflectionTestUtils.setField(service, "appId", "");
        ReflectionTestUtils.setField(service, "appSecret", "");
        assertThat(service.isReady()).isFalse();
    }

    @Test
    @DisplayName("isReady: 配置完整 → true")
    void isReadyReturnsTrueWhenConfigured() {
        assertThat(service.isReady()).isTrue();
    }

    @Test
    @DisplayName("createScanSession: 未配置凭据 → 抛 RuntimeException")
    void createScanSessionFailsWithoutCredentials() {
        ReflectionTestUtils.setField(service, "appId", "");
        ReflectionTestUtils.setField(service, "appSecret", "");
        assertThatThrownBy(service::createScanSession)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("AppID");
    }

    @Test
    @DisplayName("createScanSession: 微信返回 ticket → session 包含 showqrcode URL")
    void createScanSessionReturnsQrUrlOnSuccess() {
        stubAccessTokenAndPost(Map.of("ticket", "TICKET-ABC", "expire_seconds", 300, "url", "https://x"));

        WechatScanSession session = service.createScanSession();
        assertThat(session).isNotNull();
        assertThat(session.getSessionId()).isNotBlank();
        assertThat(session.getQrUrl()).contains("showqrcode").contains("TICKET-ABC");
        assertThat(session.getExpireSeconds()).isEqualTo(300);
        assertThat(session.getStatus()).isEqualTo(ScanStatus.SCANNING);
    }

    @Test
    @DisplayName("createScanSession: 微信返回 errcode → 抛 RuntimeException")
    void createScanSessionThrowsOnErrcode() {
        stubAccessTokenOnly();

        // post 路径返回 errcode
        doReturn(bodySpec).when(bodySpec).bodyValue(any());
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Map.class))
                .thenReturn(Mono.just(Map.of("access_token", "tok-xyz", "expires_in", 7200)))
                .thenReturn(Mono.just(Map.of("errcode", 40001, "errmsg", "invalid credential")));

        assertThatThrownBy(service::createScanSession)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("errcode");
    }

    // ── handleEvent ────────────────────────────────────────────

    @Test
    @DisplayName("handleEvent: subscribe + qrscene_ 前缀 → 标记 LOGGED_IN 并签发 token")
    void handleEventSubscribeBindsOpenid() {
        stubAccessTokenAndPost(Map.of("ticket", "TICKET-ABC", "expire_seconds", 300, "url", "x"));
        WechatScanSession created = service.createScanSession();

        User u = new User();
        u.setId(99L);
        u.setRole(User.Role.USER);
        when(authService.loginWithWechat(eq("OPENID-X"), isNull(), isNull(), eq("wechat-mp")))
                .thenReturn(new AuthResult("jwt-signed", true, u));

        String xml = "<xml>"
                + "<ToUserName><![CDATA[gh_x]]></ToUserName>"
                + "<FromUserName><![CDATA[OPENID-X]]></FromUserName>"
                + "<CreateTime>1700000000</CreateTime>"
                + "<MsgType><![CDATA[event]]></MsgType>"
                + "<Event><![CDATA[subscribe]]></Event>"
                + "<EventKey><![CDATA[qrscene_" + created.getSessionId() + "]]></EventKey>"
                + "</xml>";

        service.handleEvent(xml, null, null, null);

        WechatScanSession after = service.get(created.getSessionId());
        assertThat(after).isNotNull();
        assertThat(after.getOpenid()).isEqualTo("OPENID-X");
        assertThat(after.getStatus()).isEqualTo(ScanStatus.LOGGED_IN);
        assertThat(after.getAccessToken()).isEqualTo("jwt-signed");
        verify(authService).loginWithWechat("OPENID-X", null, null, "wechat-mp");
    }

    @Test
    @DisplayName("handleEvent: 已关注用户 SCAN → 标记 LOGGED_IN 并签发 token")
    void handleEventScanBindsOpenid() {
        stubAccessTokenAndPost(Map.of("ticket", "TICKET-ABC", "expire_seconds", 300, "url", "x"));
        WechatScanSession created = service.createScanSession();

        User u = new User();
        u.setId(100L);
        u.setRole(User.Role.USER);
        when(authService.loginWithWechat(eq("OPENID-Y"), isNull(), isNull(), eq("wechat-mp")))
                .thenReturn(new AuthResult("jwt-2", false, u));

        String xml = "<xml>"
                + "<FromUserName><![CDATA[OPENID-Y]]></FromUserName>"
                + "<Event><![CDATA[SCAN]]></Event>"
                + "<EventKey><![CDATA[" + created.getSessionId() + "]]></EventKey>"
                + "</xml>";
        service.handleEvent(xml, null, null, null);

        WechatScanSession after = service.get(created.getSessionId());
        assertThat(after.getOpenid()).isEqualTo("OPENID-Y");
        assertThat(after.getStatus()).isEqualTo(ScanStatus.LOGGED_IN);
    }

    @Test
    @DisplayName("handleEvent: 签名错误且配置了 callbackToken → 丢弃")
    void handleEventDropsInvalidSignature() {
        ReflectionTestUtils.setField(service, "callbackToken", "expected-token");
        String xml = "<xml><FromUserName><![CDATA[X]]></FromUserName>"
                + "<Event><![CDATA[subscribe]]></Event>"
                + "<EventKey><![CDATA[qrscene_abc]]></EventKey></xml>";
        service.handleEvent(xml, "wrong-sig", "1700000000", "nonce-x");
        verify(authService, never()).loginWithWechat(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("handleEvent: sessionId 不存在 → 静默忽略")
    void handleEventIgnoresUnknownSession() {
        String xml = "<xml><FromUserName><![CDATA[X]]></FromUserName>"
                + "<Event><![CDATA[SCAN]]></Event>"
                + "<EventKey><![CDATA[non-existent-session]]></EventKey></xml>";
        service.handleEvent(xml, null, null, null);
        verify(authService, never()).loginWithWechat(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("handleEvent: subscribe 但无 qrscene_ 前缀 → 忽略")
    void handleEventIgnoresNonQrsceneSubscribe() {
        String xml = "<xml><FromUserName><![CDATA[X]]></FromUserName>"
                + "<Event><![CDATA[subscribe]]></Event>"
                + "<EventKey><![CDATA[not-a-qrscene]]></EventKey></xml>";
        service.handleEvent(xml, null, null, null);
        verify(authService, never()).loginWithWechat(anyString(), any(), any(), anyString());
    }

    // ── verifySignature ─────────────────────────────────────────

    @Test
    @DisplayName("verifySignature: 错误签名 / null 入参 → false")
    void verifySignatureRejectsBadInput() throws Exception {
        Method m = WechatMpService.class.getDeclaredMethod("verifySignature",
                String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        assertThat((boolean) m.invoke(null, "signature", "1700000000", "nonce", "tk")).isFalse();
        assertThat((boolean) m.invoke(null, null, "1700000000", "nonce", "tk")).isFalse();
        assertThat((boolean) m.invoke(null, "sig", null, "nonce", "tk")).isFalse();
        assertThat((boolean) m.invoke(null, "sig", "ts", null, "tk")).isFalse();
    }
}
