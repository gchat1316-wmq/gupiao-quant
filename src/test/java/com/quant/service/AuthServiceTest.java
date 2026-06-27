package com.quant.service;

import com.quant.entity.LoginCode;
import com.quant.entity.User;
import com.quant.repository.AuditLogRepository;
import com.quant.repository.LoginCodeRepository;
import com.quant.repository.SmsCodeRepository;
import com.quant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.quant.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthService TDD 测试。
 *
 * RED phase: 写完测试 → 跑不过 → 修代码 → 测试全绿
 * 覆盖：
 * 1. 登录码生成（ADMIN 给 MANAGER / ADMIN 发码）
 * 2. 登录码登录（用码注册、码过期、码已用）
 * 3. 用户角色管理（ADMIN 改角色、查列表）
 * 4. USER 角色不允许通过登录码注册
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SmsCodeRepository smsCodeRepository;
    @Mock private LoginCodeRepository loginCodeRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private SmsService smsService;
    @Mock private JwtTokenProvider tokenProvider;
    @Mock private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, smsCodeRepository, loginCodeRepository,
                auditLogRepository, smsService, tokenProvider, passwordEncoder);
    }

    // ── 登录码生成 ───────────────────────────────────────

    @Nested
    @DisplayName("generateLoginCode")
    class GenerateLoginCode {

        @Test
        @DisplayName("ADMIN 生成 MANAGER 登录码成功")
        void adminGeneratesManagerCode() {
            when(loginCodeRepository.save(any(LoginCode.class)))
                    .thenAnswer(inv -> { LoginCode lc = inv.getArgument(0); lc.setId(1L); return lc; });

            String code = authService.generateLoginCode(99L, User.Role.MANAGER, 7);

            assertThat(code).startsWith("GP-");
            assertThat(code.length()).isGreaterThan(10);

            ArgumentCaptor<LoginCode> captor = ArgumentCaptor.forClass(LoginCode.class);
            verify(loginCodeRepository).save(captor.capture());
            LoginCode saved = captor.getValue();
            assertThat(saved.getIssuerId()).isEqualTo(99L);
            assertThat(saved.getIntendedRole()).isEqualTo(User.Role.MANAGER);
            assertThat(saved.getUsed()).isFalse();
            assertThat(saved.getExpireAt()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("ADMIN 生成 ADMIN 登录码成功")
        void adminGeneratesAdminCode() {
            when(loginCodeRepository.save(any(LoginCode.class)))
                    .thenAnswer(inv -> { LoginCode lc = inv.getArgument(0); lc.setId(2L); return lc; });

            String code = authService.generateLoginCode(99L, User.Role.ADMIN, 30);

            assertThat(code).startsWith("GP-");
            ArgumentCaptor<LoginCode> captor = ArgumentCaptor.forClass(LoginCode.class);
            verify(loginCodeRepository).save(captor.capture());
            assertThat(captor.getValue().getIntendedRole()).isEqualTo(User.Role.ADMIN);
        }

        @Test
        @DisplayName("USER 角色不允许通过登录码注册")
        void userRoleNotAllowed() {
            assertThatThrownBy(() -> authService.generateLoginCode(99L, User.Role.USER, 7))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("USER");
        }
    }

    // ── 登录码登录 ──────────────────────────────────────

    @Nested
    @DisplayName("loginWithCode")
    class LoginWithCode {

        @Test
        @DisplayName("有效登录码 → 创建用户 + 返回 token")
        void validCodeCreatesUserAndReturnsToken() {
            LoginCode validCode = new LoginCode();
            validCode.setId(1L);
            validCode.setCode("GP-20260627-ABCDEF");
            validCode.setIssuerId(99L);
            validCode.setIntendedRole(User.Role.MANAGER);
            validCode.setUsed(false);
            validCode.setExpireAt(LocalDateTime.now().plusDays(7));

            when(loginCodeRepository.findValidCode(eq("GP-20260627-ABCDEF"), any(LocalDateTime.class)))
                    .thenReturn(Optional.of(validCode));
            when(userRepository.save(any(User.class)))
                    .thenAnswer(inv -> { User u = inv.getArgument(0); u.setId(5L); return u; });
            when(tokenProvider.generate(eq(5L), eq("MANAGER"))).thenReturn("jwt-token-xyz");

            AuthService.AuthResult result = authService.loginWithCode("GP-20260627-ABCDEF", "127.0.0.1");

            assertThat(result.token()).isEqualTo("jwt-token-xyz");
            assertThat(result.isNewUser()).isTrue();

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getRole()).isEqualTo(User.Role.MANAGER);

            ArgumentCaptor<LoginCode> codeCaptor = ArgumentCaptor.forClass(LoginCode.class);
            verify(loginCodeRepository).save(codeCaptor.capture());
            assertThat(codeCaptor.getValue().getUsed()).isTrue();
            assertThat(codeCaptor.getValue().getUsedByUserId()).isEqualTo(5L);
        }

        @Test
        @DisplayName("无效登录码 → 抛异常")
        void invalidCodeThrows() {
            when(loginCodeRepository.findValidCode(any(), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.loginWithCode("BAD-CODE-123", "127.0.0.1"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("无效");
        }

        @Test
        @DisplayName("已使用的登录码 → 抛异常")
        void usedCodeThrows() {
            LoginCode usedCode = new LoginCode();
            usedCode.setUsed(true);
            usedCode.setExpireAt(LocalDateTime.now().plusDays(7));

            when(loginCodeRepository.findValidCode(any(), any(LocalDateTime.class)))
                    .thenReturn(Optional.of(usedCode));

            assertThatThrownBy(() -> authService.loginWithCode("GP-20260627-ABCDEF", "127.0.0.1"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("已使用");
        }

        @Test
        @DisplayName("过期登录码 → 找不到（不在有效期内）")
        void expiredCodeNotFound() {
            when(loginCodeRepository.findValidCode(any(), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.loginWithCode("GP-EXPIRED-123", "127.0.0.1"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("无效");
        }
    }

    // ── 用户角色管理 ────────────────────────────────────

    @Nested
    @DisplayName("用户管理")
    class UserManagement {

        @Test
        @DisplayName("ADMIN 改用户角色 → 保存新角色")
        void adminUpdatesUserRole() {
            User target = new User();
            target.setId(10L);
            target.setRole(User.Role.USER);

            when(userRepository.findById(10L)).thenReturn(Optional.of(target));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            AuthService.UserDto result = authService.updateUserRole(99L, 10L, User.Role.MANAGER);

            assertThat(result.role()).isEqualTo("MANAGER");
            verify(userRepository).save(argThat(u -> u.getRole() == User.Role.MANAGER));
        }

        @Test
        @DisplayName("ADMIN 查用户列表 → 返回所有用户 DTO")
        void listUsersReturnsAll() {
            User u1 = new User(); u1.setId(1L); u1.setUsername("Alice"); u1.setRole(User.Role.ADMIN);
            User u2 = new User(); u2.setId(2L); u2.setUsername("Bob");   u2.setRole(User.Role.MANAGER);

            when(userRepository.findAll()).thenReturn(List.of(u1, u2));

            List<AuthService.UserDto> users = authService.listUsers();

            assertThat(users).hasSize(2);
            assertThat(users.get(0).role()).isEqualTo("ADMIN");
            assertThat(users.get(1).role()).isEqualTo("MANAGER");
        }

        @Test
        @DisplayName("ADMIN 禁用用户 → setDisabled(true)")
        void adminDisablesUser() {
            User target = new User();
            target.setId(5L);
            target.setDisabled(false);

            when(userRepository.findById(5L)).thenReturn(Optional.of(target));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            authService.toggleUserDisabled(99L, 5L, true);

            verify(userRepository).save(argThat(u -> u.getDisabled()));
        }

        @Test
        @DisplayName("禁用不存在的用户 → 抛异常")
        void disableNonExistentUserThrows() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.toggleUserDisabled(99L, 999L, true))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("不存在");
        }
    }

    // ── 微信扫码登录 ─────────────────────────────────────

    @Nested
    @DisplayName("loginWithWechat")
    class LoginWithWechat {

        @Test
        @DisplayName("新用户扫码 → 创建 USER 角色账号，返回 token")
        void newUserWechatLogin() {
            when(userRepository.findByOpenid("wx_abc123")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(100L);
                return u;
            });
            when(tokenProvider.generate(100L, "USER")).thenReturn("jwt_token_new");
            when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AuthService.AuthResult result = authService.loginWithWechat("wx_abc123", "union_001", "小明", "1.2.3.4");

            assertThat(result.isNewUser()).isTrue();
            assertThat(result.token()).isEqualTo("jwt_token_new");

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            User saved = userCaptor.getValue();
            assertThat(saved.getOpenid()).isEqualTo("wx_abc123");
            assertThat(saved.getUnionid()).isEqualTo("union_001");
            assertThat(saved.getUsername()).isEqualTo("小明");
            assertThat(saved.getRole()).isEqualTo(User.Role.USER);

            // 审计日志记录注册
            verify(auditLogRepository).save(argThat(log ->
                log.getAction().equals("WECHAT_REGISTER")));
        }

        @Test
        @DisplayName("老用户扫码 → 更新昵称，返回 token，不记注册日志")
        void existingUserWechatLogin() {
            User existing = new User();
            existing.setId(50L);
            existing.setOpenid("wx_abc123");
            existing.setUsername("旧昵称");
            existing.setRole(User.Role.MANAGER);

            when(userRepository.findByOpenid("wx_abc123")).thenReturn(Optional.of(existing));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(tokenProvider.generate(50L, "MANAGER")).thenReturn("jwt_token_existing");
            when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AuthService.AuthResult result = authService.loginWithWechat("wx_abc123", "union_001", "新昵称", "5.6.7.8");

            assertThat(result.isNewUser()).isFalse();
            assertThat(result.token()).isEqualTo("jwt_token_existing");

            // 审计日志记录登录，不是注册
            verify(auditLogRepository).save(argThat(log ->
                log.getAction().equals("WECHAT_LOGIN")));
            verify(auditLogRepository, never()).save(argThat(log ->
                log.getAction().equals("WECHAT_REGISTER")));
        }

        @Test
        @DisplayName("老用户无昵称变更 → 不改 username，只记登录日志")
        void existingUserNoNicknameChange() {
            User existing = new User();
            existing.setId(60L);
            existing.setOpenid("wx_abc123");
            existing.setUsername("不变");
            existing.setRole(User.Role.USER);

            when(userRepository.findByOpenid("wx_abc123")).thenReturn(Optional.of(existing));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(tokenProvider.generate(60L, "USER")).thenReturn("jwt_token");
            when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AuthService.AuthResult result = authService.loginWithWechat("wx_abc123", null, null, "1.1.1.1");

            assertThat(result.user().getUsername()).isEqualTo("不变"); // username 未被 null 覆盖
        }
    }
}
