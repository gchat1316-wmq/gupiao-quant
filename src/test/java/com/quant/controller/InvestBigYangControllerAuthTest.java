package com.quant.controller;

import com.quant.dto.invest.BigYangAlertDTO;
import com.quant.dto.invest.BigYangRunResultDTO;
import com.quant.dto.invest.BigYangSignalDTO;
import com.quant.dto.invest.BigYangSummaryDTO;
import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.JwtAuthFilter;
import com.quant.security.JwtTokenProvider;
import com.quant.security.SecurityConfig;
import com.quant.service.InvestBigYangSignalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InvestBigYangController 角色权限测试。
 *
 * 大阳线战法面板同样归属 invest.html，写操作必须 MANAGER 或 ADMIN：
 * - GET 公开（summary / signals / alerts）
 * - POST /alerts/{id}/read → MANAGER/ADMIN
 * - POST /run → MANAGER/ADMIN
 */
@WebMvcTest(controllers = InvestBigYangController.class,
        properties = {
                "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hs256!",
                "app.sms.code-expire-minutes=5",
                "app.sms.cooldown-seconds=60"
        })
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@DisplayName("InvestBigYangController 角色权限")
class InvestBigYangControllerAuthTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider tokenProvider;

    @MockBean private InvestBigYangSignalService service;
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

    // ══════════════════════════════════════════════════
    // 公开读取接口
    // ══════════════════════════════════════════════════

    @Nested
    @DisplayName("GET 端点")
    class ReadsArePublic {

        @Test
        @DisplayName("/summary 未登录 → 200")
        void summaryAnonymous() throws Exception {
            when(service.summary()).thenReturn(BigYangSummaryDTO.builder().build());
            mvc.perform(get("/api/invest/big-yang/summary"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("/signals 未登录 → 200")
        void signalsAnonymous() throws Exception {
            when(service.signals()).thenReturn(List.of());
            mvc.perform(get("/api/invest/big-yang/signals"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("/alerts 未登录 → 200")
        void alertsAnonymous() throws Exception {
            when(service.alerts()).thenReturn(List.of());
            mvc.perform(get("/api/invest/big-yang/alerts"))
                    .andExpect(status().isOk());
        }
    }

    // ══════════════════════════════════════════════════
    // 写接口 — 必须 MANAGER 或 ADMIN
    // ══════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/invest/big-yang/alerts/{id}/read")
    class MarkRead {

        @Test
        @DisplayName("USER → 403")
        void userForbidden() throws Exception {
            mvc.perform(post("/api/invest/big-yang/alerts/42/read")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
            verify(service, never()).markAlertRead(anyLong());
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            doNothing().when(service).markAlertRead(anyLong());
            mvc.perform(post("/api/invest/big-yang/alerts/42/read")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isOk());
            verify(service).markAlertRead(anyLong());
        }

        @Test
        @DisplayName("ADMIN → 200")
        void adminAllowed() throws Exception {
            doNothing().when(service).markAlertRead(anyLong());
            mvc.perform(post("/api/invest/big-yang/alerts/42/read")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST /api/invest/big-yang/run")
    class RunScan {

        @Test
        @DisplayName("USER → 403")
        void userForbidden() throws Exception {
            mvc.perform(post("/api/invest/big-yang/run")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
            verify(service, never()).runManual();
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            when(service.runManual()).thenReturn(BigYangRunResultDTO.builder().build());
            mvc.perform(post("/api/invest/big-yang/run")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isOk());
        }
    }
}
