package com.quant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.JwtAuthFilter;
import com.quant.security.JwtTokenProvider;
import com.quant.security.SecurityConfig;
import com.quant.service.AuthService;
import com.quant.service.AuthService.AuthResult;
import com.quant.service.AuthService.UserDto;
import com.quant.service.EmailService;
import com.quant.service.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 端到端测试。
 *
 * 策略：用真实 JwtTokenProvider 生成 JWT，放在请求头 Authorization: Bearer <token>
 * 让 JwtAuthFilter 走完整的 token→User→UserPrincipal 链路。
 *
 * 公开接口（permitAll）：send-code / verify-code / login / login-code / wechat-*
 * 需要认证（anyRole）：/me / set-password
 * ADMIN 专属：/admin/*
 */
@WebMvcTest(controllers = AuthController.class,
        properties = {
                "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hs256!",
                "app.sms.code-expire-minutes=5",
                "app.sms.cooldown-seconds=60"
        })
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@DisplayName("AuthController")
class AuthControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtTokenProvider tokenProvider; // 真实 provider，用于生成测试 token
    @Autowired private AuthController authController;
    @MockBean private AuthService authService;
    @MockBean private UserRepository userRepository;
    @MockBean private com.quant.service.wechat.WechatMpService wechatMpService;
    @MockBean private SmsService smsService;
    @MockBean private EmailService emailService;

    // 预生成各类角色的真实 JWT（JwtAuthFilter 会把它们解析成 UserPrincipal）
    private String adminToken;
    private String managerToken;
    private String userToken;
    private String disabledUserToken;

    @BeforeEach
    void setUp() {
        adminToken = tokenProvider.generate(1L, "ADMIN");
        managerToken = tokenProvider.generate(2L, "MANAGER");
        userToken = tokenProvider.generate(3L, "USER");
        disabledUserToken = tokenProvider.generate(4L, "USER");

        // Mock UserRepository，让 JwtAuthFilter 能查到对应用户
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.ADMIN, false)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, User.Role.MANAGER, false)));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3L, User.Role.USER, false)));
        when(userRepository.findById(4L)).thenReturn(Optional.of(user(4L, User.Role.USER, true))); // 禁用
    }

    private static User user(Long id, User.Role role, boolean disabled) {
        User u = new User();
        u.setId(id);
        u.setPhone("1380000000" + id);
        u.setUsername("测试用户" + id);
        u.setRole(role);
        u.setDisabled(disabled);
        return u;
    }

    // ══════════════════════════════════════════════════════
    // 公开接口
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/auth/send-code")
    class SendCode {

        @Test
        @DisplayName("正常发送验证码 → 200（mock 模式回传 code 字段）")
        void sendCodeOk() throws Exception {
            when(authService.sendCode(eq("13800138000"), anyString())).thenReturn("123456");
            when(smsService.isMock()).thenReturn(true);

            mvc.perform(post("/api/auth/send-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800138000\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("验证码已发送"))
                    .andExpect(jsonPath("$.code").value("123456"));
        }

        @Test
        @DisplayName("非 mock 模式（已配真 SMS 服务）→ 不回传 code 字段")
        void sendCodeNoCodeWhenRealService() throws Exception {
            when(authService.sendCode(eq("13800138000"), anyString())).thenReturn("123456");
            when(smsService.isMock()).thenReturn(false);

            mvc.perform(post("/api/auth/send-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800138000\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("验证码已发送"))
                    .andExpect(jsonPath("$.code").doesNotExist());
        }

        @Test
        @DisplayName("手机号为空 → 200（透传给 service）")
        void emptyPhoneReturns200() throws Exception {
            when(authService.sendCode(eq(""), anyString())).thenReturn("000000");
            when(smsService.isMock()).thenReturn(true);
            mvc.perform(post("/api/auth/send-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Service 抛异常 → 400")
        void serviceThrowsReturns400() throws Exception {
            when(authService.sendCode(anyString(), anyString()))
                    .thenThrow(new RuntimeException("频率限制"));

            mvc.perform(post("/api/auth/send-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800138000\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("频率限制"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/verify-code")
    class VerifyCode {

        @Test
        @DisplayName("正确验证码 → 200 + token")
        void validCodeReturnsToken() throws Exception {
            when(authService.verifyCode("13800138000", "123456", "127.0.0.1"))
                    .thenReturn(new AuthResult("jwt-token-xyz", true, user(5L, User.Role.USER, false)));

            mvc.perform(post("/api/auth/verify-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800138000\",\"code\":\"123456\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("jwt-token-xyz"))
                    .andExpect(jsonPath("$.isNewUser").value(true))
                    .andExpect(jsonPath("$.user.username").value("测试用户5"));
        }

        @Test
        @DisplayName("错误验证码 → 400")
        void wrongCodeReturns400() throws Exception {
            when(authService.verifyCode(anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("验证码错误"));

            mvc.perform(post("/api/auth/verify-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800138000\",\"code\":\"wrong\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("验证码错误"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login")
    class Login {

        @Test
        @DisplayName("手机号 + 密码登录成功")
        void phoneLoginSuccess() throws Exception {
            when(authService.loginWithPassword("13800138000", "correct-password", "127.0.0.1"))
                    .thenReturn(new AuthResult("jwt-pwd-login", false, user(10L, User.Role.MANAGER, false)));

            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800138000\",\"password\":\"correct-password\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("jwt-pwd-login"))
                    .andExpect(jsonPath("$.isNewUser").value(false))
                    .andExpect(jsonPath("$.user.role").value("MANAGER"));
        }

        @Test
        @DisplayName("密码错误 → 400")
        void wrongPasswordReturns400() throws Exception {
            when(authService.loginWithPassword(anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("密码错误"));

            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800138000\",\"password\":\"wrong\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("密码错误"));
        }

        @Test
        @DisplayName("无账号密码字段 → 400")
        void missingFieldsReturns400() throws Exception {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── 邮箱相关接口（RED：现在还都 404，等 AuthController 加完才能 200）──

    @Nested
    @DisplayName("POST /api/auth/send-email-code")
    class SendEmailCode {

        @Test
        @DisplayName("正常发送邮箱验证码 → 200（mock 模式回传 code 字段）")
        void sendEmailCodeOk() throws Exception {
            when(authService.sendEmailCode(eq("a@b.com"), anyString())).thenReturn("654321");
            when(emailService.isMock()).thenReturn(true);

            mvc.perform(post("/api/auth/send-email-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("验证码已发送"))
                    .andExpect(jsonPath("$.code").value("654321"));
        }

        @Test
        @DisplayName("非 mock 模式（已配真邮件服务）→ 不回传 code 字段")
        void sendEmailCodeNoCodeWhenRealService() throws Exception {
            when(authService.sendEmailCode(eq("a@b.com"), anyString())).thenReturn("654321");
            when(emailService.isMock()).thenReturn(false);

            mvc.perform(post("/api/auth/send-email-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("验证码已发送"))
                    .andExpect(jsonPath("$.code").doesNotExist());
        }

        @Test
        @DisplayName("Service 抛频率限制 → 400")
        void serviceThrowsReturns400() throws Exception {
            when(authService.sendEmailCode(anyString(), anyString()))
                    .thenThrow(new RuntimeException("发送太频繁"));

            mvc.perform(post("/api/auth/send-email-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("发送太频繁"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/register-email")
    class RegisterEmail {

        @Test
        @DisplayName("新邮箱注册 → 200 + token + isNewUser=true")
        void newEmailRegisters() throws Exception {
            when(authService.registerWithEmail("new@b.com", "Pwd1234!", "127.0.0.1"))
                    .thenReturn(new AuthResult("jwt-email-new", true, user(30L, User.Role.USER, false)));

            mvc.perform(post("/api/auth/register-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"new@b.com\",\"password\":\"Pwd1234!\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("jwt-email-new"))
                    .andExpect(jsonPath("$.isNewUser").value(true))
                    .andExpect(jsonPath("$.user.role").value("USER"));
        }

        @Test
        @DisplayName("邮箱已被注册 → 400")
        void duplicateEmailReturns400() throws Exception {
            when(authService.registerWithEmail(anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("该邮箱已注册"));

            mvc.perform(post("/api/auth/register-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"dup@b.com\",\"password\":\"Pwd1234!\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("该邮箱已注册"));
        }

        @Test
        @DisplayName("缺 password 字段 → 400")
        void missingPasswordReturns400() throws Exception {
            mvc.perform(post("/api/auth/register-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/auth/verify-email-code")
    class VerifyEmailCode {

        @Test
        @DisplayName("正确验证码 → 200 + token + isNewUser=true（首次登录即注册）")
        void validCodeReturnsToken() throws Exception {
            when(authService.verifyEmailCode("new@b.com", "123456", "127.0.0.1"))
                    .thenReturn(new AuthResult("jwt-email-code", true, user(40L, User.Role.USER, false)));

            mvc.perform(post("/api/auth/verify-email-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"new@b.com\",\"code\":\"123456\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("jwt-email-code"))
                    .andExpect(jsonPath("$.isNewUser").value(true));
        }

        @Test
        @DisplayName("错误验证码 → 400")
        void wrongCodeReturns400() throws Exception {
            when(authService.verifyEmailCode(anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("验证码错误或已过期"));

            mvc.perform(post("/api/auth/verify-email-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"code\":\"wrong\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("验证码错误或已过期"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/reset-password-email")
    class ResetPasswordByEmail {

        @Test
        @DisplayName("有效验证码 → 重置密码成功")
        void resetSuccess() throws Exception {
            doNothing().when(authService).resetPasswordByEmail(eq("a@b.com"), eq("123456"), eq("NewPwd!"), anyString());

            mvc.perform(post("/api/auth/reset-password-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"code\":\"123456\",\"newPassword\":\"NewPwd!\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("密码已重置"));
        }

        @Test
        @DisplayName("用户不存在 → 400")
        void userNotFoundReturns400() throws Exception {
            doThrow(new RuntimeException("用户不存在"))
                    .when(authService).resetPasswordByEmail(anyString(), anyString(), anyString(), anyString());

            mvc.perform(post("/api/auth/reset-password-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"ghost@b.com\",\"code\":\"123456\",\"newPassword\":\"x\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("用户不存在"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/reset-password-sms")
    class ResetPasswordBySms {

        @Test
        @DisplayName("有效短信码 → 重置密码成功")
        void resetSuccess() throws Exception {
            doNothing().when(authService).resetPasswordBySms(eq("13800138000"), eq("123456"), eq("NewPwd!"), anyString());

            mvc.perform(post("/api/auth/reset-password-sms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800138000\",\"code\":\"123456\",\"newPassword\":\"NewPwd!\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("密码已重置"));
        }

        @Test
        @DisplayName("验证码错误 → 400")
        void wrongCodeReturns400() throws Exception {
            doThrow(new RuntimeException("验证码错误或已过期"))
                    .when(authService).resetPasswordBySms(anyString(), anyString(), anyString(), anyString());

            mvc.perform(post("/api/auth/reset-password-sms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"13800138000\",\"code\":\"wrong\",\"newPassword\":\"x\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("验证码错误或已过期"));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login-code")
    class LoginCode {

        @Test
        @DisplayName("有效登录码 → 200 + token")
        void validCodeReturnsToken() throws Exception {
            when(authService.loginWithCode("GP-20260628-XYZ", "127.0.0.1"))
                    .thenReturn(new AuthResult("jwt-code-abc", true, user(20L, User.Role.MANAGER, false)));

            mvc.perform(post("/api/auth/login-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"GP-20260628-XYZ\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("jwt-code-abc"));
        }

        @Test
        @DisplayName("登录码为空 → 400")
        void emptyCodeReturns400() throws Exception {
            mvc.perform(post("/api/auth/login-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("登录码不能为空"));
        }

        @Test
        @DisplayName("无效登录码 → 400")
        void invalidCodeReturns400() throws Exception {
            when(authService.loginWithCode(anyString(), anyString()))
                    .thenThrow(new RuntimeException("登录码无效或已过期"));

            mvc.perform(post("/api/auth/login-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"BAD-CODE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("登录码无效或已过期"));
        }
    }

    // ══════════════════════════════════════════════════════
    // 需要认证的接口
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/auth/me")
    class Me {

        @Test
        @DisplayName("USER token → 返回当前用户信息")
        void userMeReturnsUserInfo() throws Exception {
            // userToken → userId=3L → BeforeEach 里已 mock findById(3L)
            mvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("测试用户3"))
                    .andExpect(jsonPath("$.role").value("USER"));
        }

        @Test
        @DisplayName("MANAGER token → 返回管理员信息")
        void managerMeReturnsManagerInfo() throws Exception {
            mvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("MANAGER"));
        }

        @Test
        @DisplayName("未登录 → 401")
        void noAuthReturns401() throws Exception {
            mvc.perform(get("/api/auth/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("无效 token → 401")
        void invalidTokenReturns401() throws Exception {
            mvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /api/auth/set-password")
    class SetPassword {

        @Test
        @DisplayName("已登录 → 设置密码成功")
        void setPasswordSuccess() throws Exception {
            doNothing().when(authService).setPassword(eq(3L), anyString());

            mvc.perform(post("/api/auth/set-password")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"NewPass123!\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("密码已设置"));

            verify(authService).setPassword(eq(3L), eq("NewPass123!"));
        }

        @Test
        @DisplayName("未登录 → 401")
        void noAuthReturns401() throws Exception {
            mvc.perform(post("/api/auth/set-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"anything\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ══════════════════════════════════════════════════════
    // ADMIN 专属接口
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/auth/admin/login-code")
    class AdminLoginCode {

        @Test
        @DisplayName("ADMIN 生成登录码 → 200")
        void adminGeneratesCode() throws Exception {
            when(authService.generateLoginCode(eq(1L), eq(User.Role.MANAGER), eq(7)))
                    .thenReturn("GP-20260628-ABCDEF");

            mvc.perform(post("/api/auth/admin/login-code")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MANAGER\",\"expireDays\":7}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("GP-20260628-ABCDEF"));
        }

        @Test
        @DisplayName("MANAGER 生成登录码 → 403")
        void managerForbidden() throws Exception {
            mvc.perform(post("/api/auth/admin/login-code")
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MANAGER\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("普通 USER 生成登录码 → 403")
        void userForbidden() throws Exception {
            mvc.perform(post("/api/auth/admin/login-code")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MANAGER\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("未登录 → 403（permitAll URL 走 @PreAuthorize，Spring Security 默认 403）")
        void noAuthReturns403() throws Exception {
            mvc.perform(post("/api/auth/admin/login-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MANAGER\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /api/auth/admin/users")
    class AdminListUsers {

        @Test
        @DisplayName("ADMIN 查看用户列表 → 200")
        void adminListUsers() throws Exception {
            when(authService.listUsers()).thenReturn(List.of(
                    new UserDto(1L, "13800000001", null, null, "Alice", "ADMIN", false, null, true, false, false),
                    new UserDto(2L, "13800000002", null, null, "Bob", "MANAGER", false, null, true, false, false)
            ));

            mvc.perform(get("/api/auth/admin/users")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].role").value("ADMIN"))
                    .andExpect(jsonPath("$[1].role").value("MANAGER"));
        }

        @Test
        @DisplayName("MANAGER 查看用户列表 → 403")
        void managerForbidden() throws Exception {
            mvc.perform(get("/api/auth/admin/users")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("普通 USER 查看用户列表 → 403")
        void userForbidden() throws Exception {
            mvc.perform(get("/api/auth/admin/users")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("PUT /api/auth/admin/users/{id}/role")
    class AdminUpdateRole {

        @Test
        @DisplayName("ADMIN 改用户角色 → 200")
        void adminUpdatesRole() throws Exception {
            when(authService.updateUserRole(eq(1L), eq(10L), eq(User.Role.MANAGER)))
                    .thenReturn(new UserDto(10L, "13800000010", null, null, "Bob", "MANAGER", false, null, true, false, false));

            mvc.perform(put("/api/auth/admin/users/10/role")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MANAGER\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("MANAGER"));
        }

        @Test
        @DisplayName("USER 改用户角色 → 403")
        void userForbidden() throws Exception {
            mvc.perform(put("/api/auth/admin/users/10/role")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("PUT /api/auth/admin/users/{id}/disabled")
    class AdminToggleDisabled {

        @Test
        @DisplayName("ADMIN 禁用用户 → 200")
        void adminDisablesUser() throws Exception {
            doNothing().when(authService).toggleUserDisabled(eq(1L), eq(5L), eq(true));

            mvc.perform(put("/api/auth/admin/users/5/disabled")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"disabled\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("操作成功"));
        }

        @Test
        @DisplayName("MANAGER 禁用用户 → 403")
        void managerForbidden() throws Exception {
            mvc.perform(put("/api/auth/admin/users/5/disabled")
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"disabled\":true}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ══════════════════════════════════════════════════════
    // 微信扫码能力探测
    // ══════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/auth/wechat/qr-info")
    class WechatQrInfo {

        @BeforeEach
        void resetWechatConfig() {
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "wechatAppId", "");
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "wechatAppSecret", "");
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "mpAppId", "");
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "mpAppSecret", "");
        }

        @Test
        @DisplayName("凭据都未配置 → mode=none")
        void noneWhenAllBlank() throws Exception {
            mvc.perform(get("/api/auth/wechat/qr-info"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("none"))
                    .andExpect(jsonPath("$.mpReady").value(false))
                    .andExpect(jsonPath("$.oauthReady").value(false));
        }

        @Test
        @DisplayName("仅公众号凭据配置 → mode=mp, mpReady=true")
        void mpModeWhenMpConfigured() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "mpAppId", "wx-mp-id");
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "mpAppSecret", "wx-mp-secret");
            mvc.perform(get("/api/auth/wechat/qr-info"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("mp"))
                    .andExpect(jsonPath("$.mpReady").value(true))
                    .andExpect(jsonPath("$.oauthReady").value(false))
                    .andExpect(jsonPath("$.description").exists());
        }

        @Test
        @DisplayName("仅开放平台凭据配置 → mode=oauth, oauthReady=true")
        void oauthModeWhenOAuthConfigured() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "wechatAppId", "wx-oauth-id");
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "wechatAppSecret", "wx-oauth-secret");
            mvc.perform(get("/api/auth/wechat/qr-info"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("oauth"))
                    .andExpect(jsonPath("$.mpReady").value(false))
                    .andExpect(jsonPath("$.oauthReady").value(true));
        }

        @Test
        @DisplayName("两个凭据都配置 → mode=mp（mp 优先级更高）")
        void mpWinsOverOauth() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "wechatAppId", "wx-oauth-id");
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "wechatAppSecret", "wx-oauth-secret");
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "mpAppId", "wx-mp-id");
            org.springframework.test.util.ReflectionTestUtils.setField(authController, "mpAppSecret", "wx-mp-secret");
            mvc.perform(get("/api/auth/wechat/qr-info"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("mp"))
                    .andExpect(jsonPath("$.oauthReady").value(true));
        }
    }
}
