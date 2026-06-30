package com.quant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.QuoteDTO;
import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.JwtAuthFilter;
import com.quant.security.JwtTokenProvider;
import com.quant.security.SecurityConfig;
import com.quant.service.PracticalSelectPdfService;
import com.quant.service.PracticalSelectService;
import com.quant.service.QuoteService;
import com.quant.service.StockAnalysisPdfService;
import com.quant.service.StockAnalysisService;
import com.quant.repository.InvestPracticalSelectRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 一组小体积鉴权测试 - 验证 Quote, StockAnalysis, PracticalSelect 写端点的角色收敛：
 * - Quote: 创建/batch 创建只能 ADMIN；点赞/导入需已认证用户
 * - StockAnalysis: DELETE /record/{id} 限 ADMIN
 * - PracticalSelect: DELETE /record/{id} 限 ADMIN；分享 enable/disable 已认证即可
 */
@WebMvcTest(controllers = {QuoteController.class, StockAnalysisController.class, PracticalSelectController.class},
        properties = {
                "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hs256!"
        })
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@DisplayName("金句/个股分析/实战选股 写权限")
class QuoteAndAnalysisAuthTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtTokenProvider tokenProvider;

    @MockBean private QuoteService quoteService;
    @MockBean private StockAnalysisService stockAnalysisService;
    @MockBean private StockAnalysisPdfService stockAnalysisPdfService;
    @MockBean private PracticalSelectService practicalSelectService;
    @MockBean private PracticalSelectPdfService practicalSelectPdfService;
    @MockBean private InvestPracticalSelectRecordRepository recordRepository;
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

        when_admin();
        when_manager();
        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3L, User.Role.USER, false)));
        when(userRepository.findById(4L)).thenReturn(Optional.of(user(4L, User.Role.USER, true)));
    }

    private void when_admin() { when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.ADMIN, false))); }
    private void when_manager() { when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, User.Role.MANAGER, false))); }

    private static User user(Long id, User.Role role, boolean heavy) {
        User u = new User();
        u.setId(id);
        u.setPhone("1380000000" + id);
        u.setUsername("测试" + role);
        u.setRole(role);
        u.setDisabled(false);
        u.setNotifySms(heavy);
        return u;
    }

    // ===== QuoteController =====

    @Test
    @DisplayName("POST /api/quotes - ADMIN 可以创建金句")
    void adminCanCreateQuote() throws Exception {
        mvc.perform(post("/api/quotes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("POST /api/quotes - MANAGER 拒绝 403（写入库表，限 ADMIN）")
    void managerCannotCreateQuote() throws Exception {
        mvc.perform(post("/api/quotes")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        verify(quoteService, never()).create(any());
    }

    @Test
    @DisplayName("POST /api/quotes - USER 拒绝 403")
    void userCannotCreateQuote() throws Exception {
        mvc.perform(post("/api/quotes")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/quotes/batch - USER 拒绝 403")
    void userCannotBatchCreateQuote() throws Exception {
        mvc.perform(post("/api/quotes/batch")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/quotes/{id}/like - USER 可以点赞")
    void userCanLikeQuote() throws Exception {
        mvc.perform(post("/api/quotes/100/like")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("POST /api/quotes/{id}/import - USER 可以导入学习搭子")
    void userCanImportQuote() throws Exception {
        when(quoteService.importToStudy(eq(100L))).thenReturn(42L);
        mvc.perform(post("/api/quotes/100/import")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().is2xxSuccessful());
    }

    // ===== StockAnalysisController =====

    @Test
    @DisplayName("DELETE /api/stock-analysis/record/{id} - ADMIN 可以")
    void adminCanDeleteAnalysis() throws Exception {
        com.quant.entity.StockAnalysisRecord rec = new com.quant.entity.StockAnalysisRecord();
        rec.setId(7L);
        rec.setStatus("FAILED");
        when(stockAnalysisService.getById(7L)).thenReturn(rec);
        mvc.perform(delete("/api/stock-analysis/record/7")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("DELETE /api/stock-analysis/record/{id} - MANAGER 拒绝 403")
    void managerCannotDeleteAnalysis() throws Exception {
        mvc.perform(delete("/api/stock-analysis/record/7")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/stock-analysis/record/{id} - USER 拒绝 403")
    void userCannotDeleteAnalysis() throws Exception {
        mvc.perform(delete("/api/stock-analysis/record/7")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // ===== PracticalSelectController =====

    @Test
    @DisplayName("DELETE /api/practical-select/record/{id} - ADMIN 可以")
    void adminCanDeletePractical() throws Exception {
        mvc.perform(delete("/api/practical-select/record/7")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("DELETE /api/practical-select/record/{id} - MANAGER 拒绝 403")
    void managerCannotDeletePractical() throws Exception {
        mvc.perform(delete("/api/practical-select/record/7")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/practical-select/record/{id}/share - USER 已认证即可")
    void userCanEnableShare() throws Exception {
        when(practicalSelectService.enableShare(eq(7L), any())).thenReturn("https://x.com?token=xxx");
        mvc.perform(post("/api/practical-select/record/7/share")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("DELETE /api/practical-select/record/{id}/share - USER 已认证即可")
    void userCanDisableShare() throws Exception {
        mvc.perform(delete("/api/practical-select/record/7/share")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().is2xxSuccessful());
    }
}
