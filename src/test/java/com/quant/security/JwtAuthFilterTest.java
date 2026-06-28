package com.quant.security;

import com.quant.entity.User;
import com.quant.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * JwtAuthFilter 单元测试。
 * 覆盖：有效 token → 设置 SecurityContext、禁用用户 → 不注入、
 * 无效/过期 token → 不拦截、anonymous 降级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthFilter")
class JwtAuthFilterTest {

    @Mock private JwtTokenProvider tokenProvider;
    @Mock private UserRepository userRepository;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(tokenProvider, userRepository);
        SecurityContextHolder.clearContext();
    }

    // ── 有效 token ───────────────────────────────────────

    @Test
    @DisplayName("有效 Bearer token → 设置 Authentication")
    void validBearerTokenSetsAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token-abc");
        when(tokenProvider.getUserId("valid-token-abc")).thenReturn(42L);

        User user = new User();
        user.setId(42L);
        user.setOpenid("wx_123");
        user.setUsername("张三");
        user.setRole(User.Role.ADMIN);
        user.setDisabled(false);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        filter.doFilterInternal(request, response, filterChain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))).isTrue();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("MANAGER 用户注入 ROLE_MANAGER")
    void managerUserGetsManagerRole() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer manager-token");
        when(tokenProvider.getUserId("manager-token")).thenReturn(5L);

        User manager = new User();
        manager.setId(5L);
        manager.setRole(User.Role.MANAGER);
        manager.setDisabled(false);
        when(userRepository.findById(5L)).thenReturn(Optional.of(manager));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_MANAGER"))).isTrue();
    }

    // ── 无效 token ───────────────────────────────────────

    @Test
    @DisplayName("无 Authorization header → anonymous 降级，filterChain 继续")
    void noAuthHeaderAnonymous() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("无效 token（非 Bearer） → 不设置 Authentication")
    void nonBearerHeaderIgnored() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("token userId 为 null → 不设置 Authentication")
    void nullUserIdIgnored() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer expired-token");
        when(tokenProvider.getUserId("expired-token")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("用户不存在 → 不设置 Authentication")
    void userNotFoundIgnored() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(tokenProvider.getUserId("valid-token")).thenReturn(999L);
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    // ── 禁用用户 ──────────────────────────────────────────

    @Test
    @DisplayName("已禁用用户 → 不设置 Authentication（即使 token 有效）")
    void disabledUserNoAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer token-for-disabled");
        when(tokenProvider.getUserId("token-for-disabled")).thenReturn(7L);

        User disabledUser = new User();
        disabledUser.setId(7L);
        disabledUser.setRole(User.Role.ADMIN);
        disabledUser.setDisabled(true); // 被禁用
        when(userRepository.findById(7L)).thenReturn(Optional.of(disabledUser));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
        // 确认 filterChain 还是被调用了（禁用用户不会让请求挂掉）
    }

    @Test
    @DisplayName("禁用用户 → filterChain 仍然继续（不过滤请求）")
    void disabledUserStillPassesThroughFilterChain() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer disabled-token");
        when(tokenProvider.getUserId("disabled-token")).thenReturn(8L);
        User u = new User(); u.setId(8L); u.setDisabled(true);
        when(userRepository.findById(8L)).thenReturn(Optional.of(u));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(userRepository).findById(8L); // 用户查了，但禁用，跳过认证
    }
}
