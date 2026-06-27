package com.quant.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtTokenProvider 单元测试。
 * 覆盖：token 生成、解析、过期、role claim。
 */
@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", "test-secret-key-at-least-32-chars-long!");
        ReflectionTestUtils.setField(provider, "expireHours", 24);
        provider.init();
    }

    @Test
    @DisplayName("generate(userId, role) → parse 后 claim.role 正确")
    void generateAndParseRole() {
        String token = provider.generate(42L, "ADMIN");

        Claims claims = provider.parse(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("不同角色生成不同 token")
    void differentRolesDifferentTokens() {
        String tokenUser = provider.generate(1L, "USER");
        String tokenAdmin = provider.generate(1L, "ADMIN");

        assertThat(tokenUser).isNotEqualTo(tokenAdmin);
        assertThat(provider.parse(tokenUser).get("role")).isEqualTo("USER");
        assertThat(provider.parse(tokenAdmin).get("role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("不同用户同一角色 token 不同（sub 不同）")
    void differentUsersDifferentTokens() {
        String token1 = provider.generate(1L, "USER");
        String token2 = provider.generate(2L, "USER");

        assertThat(token1).isNotEqualTo(token2);
        assertThat(provider.parse(token1).getSubject()).isEqualTo("1");
        assertThat(provider.parse(token2).getSubject()).isEqualTo("2");
    }

    @Test
    @DisplayName("解析非法 token → 抛异常")
    void parseInvalidTokenThrows() {
        assertThat(provider.parse("not.a.valid.jwt"))
                .isNull();
    }

    @Test
    @DisplayName("null token → parse 返回 null")
    void parseNullReturnsNull() {
        assertThat(provider.parse(null)).isNull();
    }
}
