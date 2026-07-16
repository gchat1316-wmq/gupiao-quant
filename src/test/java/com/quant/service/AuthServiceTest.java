package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.quant.entity.EmailCode;
import com.quant.entity.LoginCode;
import com.quant.entity.SmsCode;
import com.quant.entity.User;
import com.quant.repository.AuditLogRepository;
import com.quant.repository.EmailCodeRepository;
import com.quant.repository.LoginCodeRepository;
import com.quant.repository.SmsCodeRepository;
import com.quant.repository.UserRepository;
import com.quant.security.JwtTokenProvider;

/**
 * AuthService TDD 测试。
 *
 * <p>RED phase: 写完测试 → 跑不过 → 修代码 → 测试全绿 覆盖： 1. 登录码生成（ADMIN 给 MANAGER / ADMIN 发码） 2.
 * 登录码登录（用码注册、码过期、码已用） 3. 用户角色管理（ADMIN 改角色、查列表） 4. USER 角色不允许通过登录码注册
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private SmsCodeRepository smsCodeRepository;
  @Mock private EmailCodeRepository emailCodeRepository;
  @Mock private LoginCodeRepository loginCodeRepository;
  @Mock private AuditLogRepository auditLogRepository;
  @Mock private SmsService smsService;
  @Mock private EmailService emailService;
  @Mock private JwtTokenProvider tokenProvider;
  @Mock private PasswordEncoder passwordEncoder;

  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService =
        new AuthService(
            userRepository,
            smsCodeRepository,
            emailCodeRepository,
            loginCodeRepository,
            auditLogRepository,
            smsService,
            emailService,
            tokenProvider,
            passwordEncoder);
  }

  // ── 登录码生成 ───────────────────────────────────────

  @Nested
  @DisplayName("generateLoginCode")
  class GenerateLoginCode {

    @Test
    @DisplayName("ADMIN 生成 MANAGER 登录码成功")
    void adminGeneratesManagerCode() {
      when(loginCodeRepository.save(any(LoginCode.class)))
          .thenAnswer(
              inv -> {
                LoginCode lc = inv.getArgument(0);
                lc.setId(1L);
                return lc;
              });

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
          .thenAnswer(
              inv -> {
                LoginCode lc = inv.getArgument(0);
                lc.setId(2L);
                return lc;
              });

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
          .thenAnswer(
              inv -> {
                User u = inv.getArgument(0);
                u.setId(5L);
                return u;
              });
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
      User u1 = new User();
      u1.setId(1L);
      u1.setUsername("Alice");
      u1.setRole(User.Role.ADMIN);
      User u2 = new User();
      u2.setId(2L);
      u2.setUsername("Bob");
      u2.setRole(User.Role.MANAGER);

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

    @Test
    @DisplayName("ADMIN 改自己角色 → 抛异常，不入库")
    void adminCannotChangeOwnRole() {
      assertThatThrownBy(() -> authService.updateUserRole(99L, 99L, User.Role.MANAGER))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("自己");
      verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("ADMIN 禁用自己的账号 → 抛异常，不入库")
    void adminCannotDisableSelf() {
      assertThatThrownBy(() -> authService.toggleUserDisabled(99L, 99L, true))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("自己");
      verify(userRepository, never()).save(any(User.class));
    }
  }

  // ── 手机号+密码 / 邮箱+密码 注册 ───────────────────────────

  @Nested
  @DisplayName("registerWithPhone")
  class RegisterWithPhone {

    @Test
    @DisplayName("手机号已存在 → 抛异常（不允许重复注册）")
    void duplicatePhoneThrows() {
      when(userRepository.existsByPhone("13800000001")).thenReturn(true);

      assertThatThrownBy(() -> authService.registerWithPhone("13800000001", "pwd", "127.0.0.1"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("已注册");
    }

    @Test
    @DisplayName("新手机号 → 创建 USER 账号，密码 hash 写入")
    void newPhoneRegisters() {
      when(userRepository.existsByPhone("13800000001")).thenReturn(false);
      when(passwordEncoder.encode("pwd")).thenReturn("$2a$hashed");
      when(userRepository.save(any(User.class)))
          .thenAnswer(
              inv -> {
                User u = inv.getArgument(0);
                u.setId(50L);
                return u;
              });
      when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      AuthService.AuthResult result =
          authService.registerWithPhone("13800000001", "pwd", "127.0.0.1");

      assertThat(result.user().getPhone()).isEqualTo("13800000001");
      assertThat(result.user().getPasswordHash()).isEqualTo("$2a$hashed");
      assertThat(result.user().getRole()).isEqualTo(User.Role.USER);
    }
  }

  @Nested
  @DisplayName("registerWithEmail")
  class RegisterWithEmail {

    @Test
    @DisplayName("邮箱已存在 → 抛异常")
    void duplicateEmailThrows() {
      when(userRepository.existsByEmail("a@b.com")).thenReturn(true);

      assertThatThrownBy(() -> authService.registerWithEmail("a@b.com", "pwd", "127.0.0.1"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("已注册");
    }

    @Test
    @DisplayName("新邮箱 → 创建 USER 账号，密码 hash 写入")
    void newEmailRegisters() {
      when(userRepository.existsByEmail("new@b.com")).thenReturn(false);
      when(passwordEncoder.encode("pwd")).thenReturn("$2a$hashed");
      when(userRepository.save(any(User.class)))
          .thenAnswer(
              inv -> {
                User u = inv.getArgument(0);
                u.setId(60L);
                return u;
              });
      when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      AuthService.AuthResult result =
          authService.registerWithEmail("new@b.com", "pwd", "127.0.0.1");

      assertThat(result.user().getEmail()).isEqualTo("new@b.com");
      assertThat(result.user().getPasswordHash()).isEqualTo("$2a$hashed");
      assertThat(result.user().getRole()).isEqualTo(User.Role.USER);
    }
  }

  // ── 密码登录支持邮箱 ─────────────────────────────────

  @Nested
  @DisplayName("loginWithPassword 扩展：identifier 支持邮箱")
  class LoginWithPasswordEmail {

    @Test
    @DisplayName("用邮箱 + 密码登录成功")
    void emailLoginSuccess() {
      User u = new User();
      u.setId(7L);
      u.setEmail("a@b.com");
      u.setRole(User.Role.USER);
      u.setPasswordHash("$2a$hash");
      u.setDisabled(false);
      when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(u));
      when(passwordEncoder.matches("pwd", "$2a$hash")).thenReturn(true);
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
      when(tokenProvider.generate(7L, "USER")).thenReturn("jwt-email-login");

      AuthService.AuthResult result = authService.loginWithPassword("a@b.com", "pwd", "127.0.0.1");

      assertThat(result.token()).isEqualTo("jwt-email-login");
      assertThat(result.user().getEmail()).isEqualTo("a@b.com");
    }
  }

  // ── 邮箱验证码 ──────────────────────────────────────────

  @Nested
  @DisplayName("sendEmailCode")
  class SendEmailCode {

    @Test
    @DisplayName("60秒内重复发送 → 抛频率限制")
    void cooldownThrows() {
      EmailCode existing = new EmailCode();
      existing.setEmail("a@b.com");
      when(emailCodeRepository.findValidCode(eq("a@b.com"), any(LocalDateTime.class)))
          .thenReturn(existing);
      assertThatThrownBy(() -> authService.sendEmailCode("a@b.com", "127.0.0.1"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("频繁");
    }

    @Test
    @DisplayName("正常发送 → 保存新码 + 标记旧码")
    void sendSucceeds() {
      when(emailCodeRepository.findValidCode(eq("a@b.com"), any())).thenReturn(null);
      when(emailService.generateCode()).thenReturn("654321");
      when(emailService.sendCode("a@b.com", "654321")).thenReturn("654321");
      when(emailCodeRepository.save(any(EmailCode.class)))
          .thenAnswer(
              inv -> {
                EmailCode e = inv.getArgument(0);
                e.setId(1L);
                return e;
              });

      authService.sendEmailCode("a@b.com", "127.0.0.1");

      verify(emailCodeRepository).markUsed("a@b.com");
      ArgumentCaptor<EmailCode> captor = ArgumentCaptor.forClass(EmailCode.class);
      verify(emailCodeRepository).save(captor.capture());
      assertThat(captor.getValue().getEmail()).isEqualTo("a@b.com");
      assertThat(captor.getValue().getCode()).isEqualTo("654321");
      assertThat(captor.getValue().getExpireAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("邮件发送失败 → 抛异常")
    void sendFailureThrows() {
      when(emailCodeRepository.findValidCode(any(), any())).thenReturn(null);
      when(emailService.generateCode()).thenReturn("111111");
      when(emailService.sendCode(anyString(), anyString())).thenReturn(null);

      assertThatThrownBy(() -> authService.sendEmailCode("a@b.com", "127.0.0.1"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("邮件发送失败");
    }
  }

  @Nested
  @DisplayName("verifyEmailCode")
  class VerifyEmailCode {

    @Test
    @DisplayName("新邮箱 → 自动注册 USER，返回 token")
    void newEmailRegisters() {
      EmailCode record = new EmailCode();
      record.setEmail("new@b.com");
      record.setCode("123456");
      record.setUsed(false);
      when(emailCodeRepository.findValidCode(eq("new@b.com"), any())).thenReturn(record);
      when(userRepository.findByEmail("new@b.com")).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class)))
          .thenAnswer(
              inv -> {
                User u = inv.getArgument(0);
                u.setId(99L);
                return u;
              });
      when(tokenProvider.generate(99L, "USER")).thenReturn("jwt-new-email");
      when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      AuthService.AuthResult result =
          authService.verifyEmailCode("new@b.com", "123456", "127.0.0.1");

      assertThat(result.isNewUser()).isTrue();
      assertThat(result.token()).isEqualTo("jwt-new-email");
      ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
      verify(userRepository).save(captor.capture());
      assertThat(captor.getValue().getEmail()).isEqualTo("new@b.com");
    }

    @Test
    @DisplayName("错误验证码 → 抛异常")
    void wrongCodeThrows() {
      when(emailCodeRepository.findValidCode(any(), any())).thenReturn(null);
      assertThatThrownBy(() -> authService.verifyEmailCode("a@b.com", "wrong", "127.0.0.1"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("验证码错误");
    }
  }

  // ── 密码重置 ───────────────────────────────────────────

  @Nested
  @DisplayName("resetPasswordBySms")
  class ResetPasswordBySms {

    @Test
    @DisplayName("有效短信码 + 已有用户 → 改密码 hash 成功")
    void successResetsPassword() {
      SmsCode record = new SmsCode();
      record.setCode("123456");
      User u = new User();
      u.setId(11L);
      u.setPhone("13800000001");
      when(smsCodeRepository.findValidCode(eq("13800000001"), any())).thenReturn(record);
      when(userRepository.findByPhone("13800000001")).thenReturn(Optional.of(u));
      when(passwordEncoder.encode("newPwd")).thenReturn("$2a$new");
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      authService.resetPasswordBySms("13800000001", "123456", "newPwd", "127.0.0.1");

      verify(userRepository).save(argThat(user -> "$2a$new".equals(user.getPasswordHash())));
      verify(smsCodeRepository).save(argThat(s -> Boolean.TRUE.equals(s.getUsed())));
    }

    @Test
    @DisplayName("用户不存在 → 抛异常")
    void userNotFoundThrows() {
      SmsCode record = new SmsCode();
      record.setCode("123456");
      when(smsCodeRepository.findValidCode(eq("13800000001"), any())).thenReturn(record);
      when(userRepository.findByPhone("13800000001")).thenReturn(Optional.empty());

      assertThatThrownBy(
              () -> authService.resetPasswordBySms("13800000001", "123456", "newPwd", "127.0.0.1"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("用户不存在");
    }
  }

  @Nested
  @DisplayName("resetPasswordByEmail")
  class ResetPasswordByEmail {

    @Test
    @DisplayName("有效邮箱码 + 已有用户 → 改密码 hash 成功")
    void successResetsPassword() {
      EmailCode record = new EmailCode();
      record.setCode("123456");
      User u = new User();
      u.setId(22L);
      u.setEmail("a@b.com");
      when(emailCodeRepository.findValidCode(eq("a@b.com"), any())).thenReturn(record);
      when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(u));
      when(passwordEncoder.encode("newPwd")).thenReturn("$2a$new");
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      authService.resetPasswordByEmail("a@b.com", "123456", "newPwd", "127.0.0.1");

      verify(userRepository).save(argThat(user -> "$2a$new".equals(user.getPasswordHash())));
      verify(emailCodeRepository).save(argThat(e -> Boolean.TRUE.equals(e.getUsed())));
    }

    @Test
    @DisplayName("用户不存在 → 抛异常")
    void userNotFoundThrows() {
      EmailCode record = new EmailCode();
      record.setCode("123456");
      when(emailCodeRepository.findValidCode(eq("a@b.com"), any())).thenReturn(record);
      when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.empty());

      assertThatThrownBy(
              () -> authService.resetPasswordByEmail("a@b.com", "123456", "newPwd", "127.0.0.1"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("用户不存在");
    }
  }

  // ── updateProfile：改手机号需要短信验证 ───────────────────────

  @Nested
  @DisplayName("updateProfile")
  class UpdateProfile {

    @Test
    @DisplayName("不改手机号 → 跳过验证码，直接更新")
    void samePhoneSkipsCode() {
      User u = new User();
      u.setId(7L);
      u.setPhone("13800000001");
      when(userRepository.findById(7L)).thenReturn(Optional.of(u));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      AuthService.UserDto dto =
          authService.updateProfile(7L, "13800000001", null, null, true, true, false, "127.0.0.1");

      assertThat(dto.phone()).isEqualTo("13800000001");
      verify(smsCodeRepository, never()).findValidCode(anyString(), any());
    }

    @Test
    @DisplayName("改手机号未传验证码 → 抛异常，不入库")
    void changePhoneWithoutCodeThrows() {
      User u = new User();
      u.setId(7L);
      u.setPhone("13800000001");
      when(userRepository.findById(7L)).thenReturn(Optional.of(u));

      assertThatThrownBy(
              () ->
                  authService.updateProfile(
                      7L, "13800000099", null, null, null, null, null, "127.0.0.1"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("验证码");
      verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("改手机号验证码错误 → 抛异常")
    void changePhoneWithWrongCodeThrows() {
      User u = new User();
      u.setId(7L);
      u.setPhone("13800000001");
      when(userRepository.findById(7L)).thenReturn(Optional.of(u));

      SmsCode record = new SmsCode();
      record.setCode("111111");
      when(smsCodeRepository.findValidCode(eq("13800000099"), any())).thenReturn(record);

      assertThatThrownBy(
              () ->
                  authService.updateProfile(
                      7L, "13800000099", "999999", null, null, null, null, "127.0.0.1"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("验证码");
      verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("改手机号验证码正确 → 改号成功，验证码标记已用")
    void changePhoneWithCorrectCodeSucceeds() {
      User u = new User();
      u.setId(7L);
      u.setPhone("13800000001");
      when(userRepository.findById(7L)).thenReturn(Optional.of(u));
      when(userRepository.existsByPhone("13800000099")).thenReturn(false);
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      SmsCode record = new SmsCode();
      record.setCode("123456");
      when(smsCodeRepository.findValidCode(eq("13800000099"), any())).thenReturn(record);

      AuthService.UserDto dto =
          authService.updateProfile(
              7L, "13800000099", "123456", null, null, null, null, "127.0.0.1");

      assertThat(dto.phone()).isEqualTo("13800000099");
      verify(smsCodeRepository).save(argThat(s -> Boolean.TRUE.equals(s.getUsed())));
      verify(userRepository).save(argThat(user -> "13800000099".equals(user.getPhone())));
    }

    @Test
    @DisplayName("改手机号目标号已被占用 → 抛异常")
    void changePhoneToOccupiedNumberThrows() {
      User u = new User();
      u.setId(7L);
      u.setPhone("13800000001");
      when(userRepository.findById(7L)).thenReturn(Optional.of(u));

      SmsCode record = new SmsCode();
      record.setCode("123456");
      when(smsCodeRepository.findValidCode(eq("13800000099"), any())).thenReturn(record);
      when(userRepository.existsByPhone("13800000099")).thenReturn(true);

      assertThatThrownBy(
              () ->
                  authService.updateProfile(
                      7L, "13800000099", "123456", null, null, null, null, "127.0.0.1"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("占用");
      verify(userRepository, never()).save(any(User.class));
    }
  }
}
