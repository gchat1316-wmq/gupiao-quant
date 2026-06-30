package com.quant.controller;

import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.JwtAuthFilter;
import com.quant.security.JwtTokenProvider;
import com.quant.security.SecurityConfig;
import com.quant.service.StockQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * StockController 4 个 /admin/* 端点的角色权限测试。
 *
 * 期望：所有 /admin/* 端点必须 ADMIN 才能调用。USER/MANAGER 调用应被拒绝（403）。
 */
@WebMvcTest(controllers = StockController.class,
        properties = {
                "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hs256!"
        })
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@DisplayName("StockController /admin/* 角色权限")
class StockControllerAdminAuthTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider tokenProvider;

    @MockBean private StockQueryService stockQueryService;
    @MockBean private CacheManager cacheManager;
    @MockBean private DataSource dataSource;
    @MockBean private UserRepository userRepository;

    private String adminToken;
    private String managerToken;
    private String heavyUserToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        adminToken = tokenProvider.generate(1L, "ADMIN");
        managerToken = tokenProvider.generate(2L, "MANAGER");
        heavyUserToken = tokenProvider.generate(3L, "USER");
        userToken = tokenProvider.generate(4L, "USER");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.ADMIN, false)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, User.Role.MANAGER, false)));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3L, User.Role.USER, false, true)));
        when(userRepository.findById(4L)).thenReturn(Optional.of(user(4L, User.Role.USER, false, false)));

        Cache cache = org.mockito.Mockito.mock(Cache.class);
        when(cacheManager.getCache(any())).thenReturn(cache);
    }

    private static User user(Long id, User.Role role, boolean disabled) {
        return user(id, role, disabled, false);
    }

    private static User user(Long id, User.Role role, boolean disabled, boolean heavy) {
        User u = new User();
        u.setId(id);
        u.setPhone("1380000000" + id);
        u.setUsername("测试" + role);
        u.setRole(role);
        u.setDisabled(disabled);
        u.setNotifySms(heavy);
        return u;
    }

    @Test
    @DisplayName("POST /admin/fix-annual-to-quarterly - ADMIN 可以")
    void adminCanFixAnnualToQuarterly() throws Exception {
        when(stockQueryService.fixAnnualToQuarterlyRevenue()).thenReturn(0);
        mvc.perform(post("/api/stock/admin/fix-annual-to-quarterly")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("POST /admin/fix-annual-to-quarterly - MANAGER 拒绝 403")
    void managerCannotFixAnnualToQuarterly() throws Exception {
        mvc.perform(post("/api/stock/admin/fix-annual-to-quarterly")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
        verify(stockQueryService, never()).fixAnnualToQuarterlyRevenue();
    }

    @Test
    @DisplayName("POST /admin/fix-annual-to-quarterly - USER 拒绝 403")
    void userCannotFixAnnualToQuarterly() throws Exception {
        mvc.perform(post("/api/stock/admin/fix-annual-to-quarterly")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        verify(stockQueryService, never()).fixAnnualToQuarterlyRevenue();
    }

    @Test
    @DisplayName("POST /admin/fix-annual-to-quarterly - 未登录 401")
    void anonymousCannotFixAnnualToQuarterly() throws Exception {
        mvc.perform(post("/api/stock/admin/fix-annual-to-quarterly"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /admin/clear-yoy-cache - USER 拒绝 403")
    void userCannotClearYoyCache() throws Exception {
        mvc.perform(post("/api/stock/admin/clear-yoy-cache")
                        .param("code", "600519.SH")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /admin/force-fix-field - MANAGER 拒绝 403")
    void managerCannotForceFixField() throws Exception {
        mvc.perform(post("/api/stock/admin/force-fix-field")
                        .param("code", "603259.SH")
                        .param("field", "revenue")
                        .param("date", "2025-12-31")
                        .param("value", "125.99")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/debug-db-row - USER 拒绝 403（这是敏感的数据导出，不应让普通用户能调）")
    void userCannotDebugDbRow() throws Exception {
        mvc.perform(get("/api/stock/admin/debug-db-row")
                        .param("code", "600519.SH")
                        .param("year", "2025")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
