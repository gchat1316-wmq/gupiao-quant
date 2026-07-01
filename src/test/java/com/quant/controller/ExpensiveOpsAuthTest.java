package com.quant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.JwtAuthFilter;
import com.quant.security.JwtTokenProvider;
import com.quant.security.SecurityConfig;
import com.quant.service.ProsperityPickService;
import com.quant.service.prosperitystrong.ProsperityDataProviderService;
import com.quant.service.prosperitystrong.ProsperityPoolService;
import com.quant.service.prosperitystrong.ProsperityStrongPipelineService;
import com.quant.service.prosperitystrong.WindAifinMarketClient;
import com.quant.service.tdx.TdxMcpClient;
import com.quant.service.tdx.TdxOAuthClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 3 个高成本 AI / 外部集成的写端点权限收敛测试。
 *
 * 规则：
 * - ProsperityStrong POST /run / DELETE /runs/{date}: MANAGER 或 ADMIN
 * - ProsperityPick POST /{id}/infographic: MANAGER 或 ADMIN（懒生图片，AI 调用）
 * - TdxAuth GET /start / GET /logout: MANAGER 或 ADMIN（外部 OAuth 集成）
 *
 * 重度 USER 与普通 USER 一律 403。
 */
@WebMvcTest(controllers = {
        ProsperityStrongController.class,
        ProsperityPickController.class,
        TdxAuthController.class
    },
    properties = {
        "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hs256!"
    })
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@DisplayName("昂贵操作权限收敛 (MANAGER/ADMIN)")
class ExpensiveOpsAuthTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtTokenProvider tokenProvider;

    @MockBean private ProsperityStrongPipelineService prosperityPipeline;
    @MockBean private ProsperityDataProviderService prosperityProviders;
    @MockBean private WindAifinMarketClient windClient;
    @MockBean private ProsperityPoolService prosperityPoolService;

    @MockBean private ProsperityPickService prosperityPickService;

    @MockBean private TdxOAuthClient oauthClient;
    @MockBean private TdxMcpClient mcpClient;

    @MockBean private UserRepository userRepository;

    private String adminToken;
    private String managerToken;
    private String userToken;
    private String heavyUserToken;

    @BeforeEach
    void setUp() {
        adminToken = tokenProvider.generate(1L, "ADMIN");
        managerToken = tokenProvider.generate(2L, "MANAGER");
        userToken = tokenProvider.generate(3L, "USER");
        heavyUserToken = tokenProvider.generate(4L, "USER");

        when(userRepository.findById(1L)).thenReturn(Optional.of(u(1L, User.Role.ADMIN)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(u(2L, User.Role.MANAGER)));
        when(userRepository.findById(3L)).thenReturn(Optional.of(u(3L, User.Role.USER, false)));
        when(userRepository.findById(4L)).thenReturn(Optional.of(u(4L, User.Role.USER, true)));
    }

    private static User u(Long id, User.Role role) { return u(id, role, false); }
    private static User u(Long id, User.Role role, boolean heavy) {
        User u = new User();
        u.setId(id);
        u.setPhone("1380000000" + id);
        u.setUsername("测试" + role);
        u.setRole(role);
        u.setDisabled(false);
        u.setNotifySms(heavy);
        return u;
    }

    // ===== ProsperityStrongController =====

    @Test
    @DisplayName("POST /api/prosperity-strong/run - USER 拒绝 403")
    void userCannotRunProsperity() throws Exception {
        mvc.perform(post("/api/prosperity-strong/run")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        verify(prosperityPipeline, never()).run(any(), any());
    }

    @Test
    @DisplayName("POST /api/prosperity-strong/run - 重度 USER 拒绝 403")
    void heavyUserCannotRunProsperity() throws Exception {
        mvc.perform(post("/api/prosperity-strong/run")
                        .header("Authorization", "Bearer " + heavyUserToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/prosperity-strong/runs/{date} - USER 拒绝 403（删运行数据很危险）")
    void userCannotDeleteRun() throws Exception {
        mvc.perform(delete("/api/prosperity-strong/runs/2026-06-30")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // ===== ProsperityPickController =====

    @Test
    @DisplayName("POST /api/invest/prosperity-pick/{id}/infographic - USER 拒绝 403（懒生图=AI token 消耗）")
    void userCannotGenerateInfographic() throws Exception {
        mvc.perform(post("/api/invest/prosperity-pick/42/infographic")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        verify(prosperityPickService, never()).generateInfographic(42L);
    }

    @Test
    @DisplayName("POST /api/invest/prosperity-pick/{id}/infographic - MANAGER 可以")
    void managerCanGenerateInfographic() throws Exception {
        when(prosperityPickService.generateInfographic(42L)).thenReturn("/uploads/infographic/42.png");
        mvc.perform(post("/api/invest/prosperity-pick/42/infographic")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().is2xxSuccessful());
    }

    // ===== TdxAuthController =====

    @Test
    @DisplayName("GET /api/tdx/auth/start - USER 拒绝 403（外部 OAuth 集成不应该让普通用户调）")
    void userCannotStartTdxAuth() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tdx/auth/start")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/tdx/auth/logout - USER 拒绝 403")
    void userCannotLogoutTdx() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/tdx/auth/logout")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

}
