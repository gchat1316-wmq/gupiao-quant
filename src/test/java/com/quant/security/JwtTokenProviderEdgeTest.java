package com.quant.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtTokenProvider 扩展测试。
 * 覆盖：过期 token、getUserId/getRole helper、空字符串、篡改检测。
 */
@DisplayName("JwtTokenProvider 边界")
class JwtTokenProviderEdgeTest {

    private JwtTokenProvider provider;

    private void init(String secret, int expireHours) {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", secret);
        ReflectionTestUtils.setField(provider, "expireHours", expireHours);
        provider.init();
    }

    // ── helper 方法 ───────────────────────────────────────

    @Test
    @DisplayName("getUserId 返回正确的 userId")
    void getUserIdReturnsCorrectId() {
        init("test-secret-key-at-least-32-chars-long!", 24);
        String token = provider.generate(99L, "ADMIN");

        assertThat(provider.getUserId(token)).isEqualTo(99L);
    }

    @Test
    @DisplayName("getRole 返回正确的 role")
    void getRoleReturnsCorrectRole() {
        init("test-secret-key-at-least-32-chars-long!", 24);
        String token = provider.generate(1L, "MANAGER");

        assertThat(provider.getRole(token)).isEqualTo("MANAGER");
    }

    @Test
    @DisplayName("无效 token → getUserId 返回 null")
    void getUserIdOnInvalidTokenReturnsNull() {
        init("test-secret-key-at-least-32-chars-long!", 24);
        assertThat(provider.getUserId("bad.token.here")).isNull();
    }

    @Test
    @DisplayName("无效 token → getRole 返回 null")
    void getRoleOnInvalidTokenReturnsNull() {
        init("test-secret-key-at-least-32-chars-long!", 24);
        assertThat(provider.getRole("bad.token.here")).isNull();
    }

    // ── 过期 token ───────────────────────────────────────

    @Nested
    @DisplayName("token 过期")
    class ExpiredToken {

        @Test
        @DisplayName("已过期的 token → parse 返回 null")
        void expiredTokenReturnsNull() {
            // 1ms 有效期 = 立即过期
            init("test-secret-key-at-least-32-chars-long!", 0);
            String token = provider.generate(1L, "USER");

            assertThat(provider.parse(token)).isNull();
        }

        @Test
        @DisplayName("已过期的 token → getUserId 返回 null")
        void expiredTokenGetUserIdReturnsNull() {
            init("test-secret-key-at-least-32-chars-long!", 0);
            String token = provider.generate(1L, "USER");

            assertThat(provider.getUserId(token)).isNull();
        }
    }

    // ── 篡改检测 ──────────────────────────────────────────

    @Nested
    @DisplayName("token 篡改检测")
    class TamperDetection {

        @BeforeEach
        void setUp() {
            init("test-secret-key-at-least-32-chars-long!", 24);
        }

        @Test
        @DisplayName("修改 payload 部分 → parse 返回 null")
        void tamperedPayloadReturnsNull() {
            String token = provider.generate(1L, "USER");
            // 替换 payload 中的角色字段
            String tampered = token.substring(0, token.length() - 5) + "XXXXX";
            assertThat(provider.parse(tampered)).isNull();
        }

        @Test
        @DisplayName("空字符串 token → parse 返回 null")
        void emptyStringReturnsNull() {
            assertThat(provider.parse("")).isNull();
        }

        @Test
        @DisplayName("空白字符串 token → parse 返回 null")
        void blankStringReturnsNull() {
            assertThat(provider.parse("   ")).isNull();
        }

        @Test
        @DisplayName("伪造 token（只用三段） → parse 返回 null")
        void fakeJwtStructureReturnsNull() {
            // JWT 格式但用不同密钥签名
            String fake = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.fake-signature";
            assertThat(provider.parse(fake)).isNull();
        }
    }

    // ── 密钥变化 ──────────────────────────────────────────

    @Nested
    @DisplayName("密钥不一致")
    class KeyMismatch {

        @Test
        @DisplayName("不同密钥签名的 token → parse 返回 null")
        void differentKeyReturnsNull() {
            init("test-secret-key-at-least-32-chars-long!", 24);
            String token = provider.generate(1L, "USER");

            // 用不同密钥初始化
            init("different-secret-key-at-least-32c!", 24);
            assertThat(provider.parse(token)).isNull();
        }
    }
}
