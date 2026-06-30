package com.quant.controller;

import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.JwtAuthFilter;
import com.quant.security.JwtTokenProvider;
import com.quant.security.SecurityConfig;
import com.quant.service.xieboinvest.XieboInvestAnalysisService;
import com.quant.service.xieboinvest.XieboInvestNewsService;
import com.quant.service.xieboinvest.XieboInvestService;
import com.quant.service.xieboinvest.XieboWeeklyOpportunityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 谢博投资控制器角色权限测试。
 *
 * 规则：
 * - 读接口（GET /watchlist、/quote、/sector-pe、/news、/analysis、/analysis/{id}）保持已认证即可访问。
 * - 写接口（POST /watchlist、DELETE /watchlist/{code}、POST /analysis）必须 MANAGER 或 ADMIN。
 * - USER 角色访问写接口 → 403，service 不应被调用。
 */
@WebMvcTest(controllers = XieboInvestController.class,
        properties = {
                "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hs256!",
                "app.sms.code-expire-minutes=5",
                "app.sms.cooldown-seconds=60"
        })
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@DisplayName("XieboInvestController 角色权限")
class XieboInvestControllerAuthTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider tokenProvider;

    @MockBean private XieboInvestService service;
    @MockBean private XieboInvestAnalysisService analysisService;
    @MockBean private XieboInvestNewsService newsService;
    @MockBean private XieboWeeklyOpportunityService weeklyOpportunityService;
    @MockBean private UserRepository userRepository;

    private String adminToken;
    private String managerToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        adminToken = tokenProvider.generate(1L, "ADMIN");
        managerToken = tokenProvider.generate(2L, "MANAGER");
        userToken = tokenProvider.generate(3L, "USER");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.ADMIN, false)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, User.Role.MANAGER, false)));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3L, User.Role.USER, false)));
    }

    private static User user(Long id, User.Role role, boolean disabled) {
        User u = new User();
        u.setId(id);
        u.setPhone("1380000000" + id);
        u.setUsername("测试" + role);
        u.setRole(role);
        u.setDisabled(disabled);
        return u;
    }

    // ── 读接口 - 已认证即可 ──

    @Test
    @DisplayName("GET /watchlist - USER 可访问")
    void userCanReadWatchlist() throws Exception {
        when(service.getWatchlist()).thenReturn(List.of());
        mvc.perform(get("/api/xiebo-invest/watchlist").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /quote - USER 可访问")
    void userCanReadQuote() throws Exception {
        when(service.getQuote(anyString())).thenReturn(null);
        mvc.perform(get("/api/xiebo-invest/quote").param("keyword", "002371")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /analysis - USER 可访问")
    void userCanListAnalysis() throws Exception {
        when(analysisService.list()).thenReturn(List.of());
        mvc.perform(get("/api/xiebo-invest/analysis").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    // ── 写接口 - 必须 MANAGER / ADMIN ──

    @Test
    @DisplayName("POST /watchlist - USER 403")
    void userCannotAddWatchlist() throws Exception {
        mvc.perform(post("/api/xiebo-invest/watchlist")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json")
                        .content("{\"keyword\":\"002371\"}"))
                .andExpect(status().isForbidden());

        verify(service, never()).addWatchlist(any());
    }

    @Test
    @DisplayName("POST /watchlist - MANAGER 200")
    void managerCanAddWatchlist() throws Exception {
        when(service.addWatchlist(anyString())).thenReturn(List.of());
        mvc.perform(post("/api/xiebo-invest/watchlist")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType("application/json")
                        .content("{\"keyword\":\"002371\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /watchlist - ADMIN 200")
    void adminCanAddWatchlist() throws Exception {
        when(service.addWatchlist(anyString())).thenReturn(List.of());
        mvc.perform(post("/api/xiebo-invest/watchlist")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"keyword\":\"002371\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /watchlist/{code} - USER 403")
    void userCannotRemoveWatchlist() throws Exception {
        mvc.perform(delete("/api/xiebo-invest/watchlist/002371")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        verify(service, never()).removeWatchlist(anyString());
    }

    @Test
    @DisplayName("DELETE /watchlist/{code} - MANAGER 200")
    void managerCanRemoveWatchlist() throws Exception {
        mvc.perform(delete("/api/xiebo-invest/watchlist/002371")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /analysis - USER 403")
    void userCannotCreateAnalysis() throws Exception {
        mvc.perform(post("/api/xiebo-invest/analysis")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json")
                        .content("{\"keyword\":\"002371\"}"))
                .andExpect(status().isForbidden());

        verify(analysisService, never()).create(anyString());
    }

    @Test
    @DisplayName("POST /analysis - MANAGER 200")
    void managerCanCreateAnalysis() throws Exception {
        when(analysisService.create(anyString())).thenReturn(null);
        mvc.perform(post("/api/xiebo-invest/analysis")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType("application/json")
                        .content("{\"keyword\":\"002371\"}"))
                .andExpect(status().isOk());
    }

    // ── 本周重点股票（3×3 卡片）─────────────────────────────────

    @Test
    @DisplayName("GET /weekly-opportunity - USER 可访问")
    void userCanReadWeeklyOpportunity() throws Exception {
        when(weeklyOpportunityService.listAll()).thenReturn(List.of());
        mvc.perform(get("/api/xiebo-invest/weekly-opportunity")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /weekly-opportunity/{poolType} - USER 403")
    void userCannotUpdateWeeklyOpportunity() throws Exception {
        mvc.perform(put("/api/xiebo-invest/weekly-opportunity/watch")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json")
                        .content("{\"slots\":[]}"))
                .andExpect(status().isForbidden());

        verify(weeklyOpportunityService, never()).update(any(), any());
    }

    @Test
    @DisplayName("PUT /weekly-opportunity/{poolType} - MANAGER 200")
    void managerCanUpdateWeeklyOpportunity() throws Exception {
        when(weeklyOpportunityService.update(any(), any())).thenReturn(List.of());
        mvc.perform(put("/api/xiebo-invest/weekly-opportunity/watch")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType("application/json")
                        .content("{\"slots\":[]}"))
                .andExpect(status().isOk());
    }
}
