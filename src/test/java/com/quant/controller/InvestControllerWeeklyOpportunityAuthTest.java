package com.quant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.invest.WeeklyOpportunitySlotDTO;
import com.quant.dto.invest.WeeklyOpportunityUpdateRequest;
import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.JwtAuthFilter;
import com.quant.security.JwtTokenProvider;
import com.quant.security.SecurityConfig;
import com.quant.service.InvestPoolMetaService;
import com.quant.service.InvestPoolRefreshService;
import com.quant.service.InvestPoolSeedService;
import com.quant.service.InvestService;
import com.quant.service.InvestWeeklyOpportunityService;
import com.quant.service.OcrPoolImportService;
import com.quant.service.PriceMonitorService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 每周机会点接口的权限测试。
 *
 * 规则：
 * - GET（读取）公开（与现有 /pool-meta 一致）
 * - PUT（写）必须 MANAGER 或 ADMIN
 */
@WebMvcTest(controllers = InvestController.class,
        properties = {
                "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hs256!",
                "app.sms.code-expire-minutes=5",
                "app.sms.cooldown-seconds=60"
        })
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@DisplayName("InvestController 每周机会点权限")
class InvestControllerWeeklyOpportunityAuthTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtTokenProvider tokenProvider;

    @MockBean private InvestService investService;
    @MockBean private OcrPoolImportService ocrService;
    @MockBean private PriceMonitorService priceMonitorService;
    @MockBean private InvestPoolSeedService poolSeedService;
    @MockBean private InvestPoolRefreshService poolRefreshService;
    @MockBean private InvestPoolMetaService poolMetaService;
    @MockBean private InvestWeeklyOpportunityService weeklyOpportunityService;
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
    // 公开读取
    // ══════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/invest/weekly-opportunity/{poolType}")
    class ReadSlot {

        @Test
        @DisplayName("未登录 → 200")
        void anonymousCanRead() throws Exception {
            when(weeklyOpportunityService.get("tech_vc")).thenReturn(List.of());
            mvc.perform(get("/api/invest/weekly-opportunity/tech_vc"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("USER → 200")
        void userCanRead() throws Exception {
            when(weeklyOpportunityService.get("innovative_drug")).thenReturn(List.of());
            mvc.perform(get("/api/invest/weekly-opportunity/innovative_drug")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk());
        }
    }

    // ══════════════════════════════════════════════════
    // 写接口 — 必须 MANAGER 或 ADMIN
    // ══════════════════════════════════════════════════

    @Nested
    @DisplayName("PUT /api/invest/weekly-opportunity/{poolType}")
    class UpdateSlots {

        @Test
        @DisplayName("USER → 403，且 service 不被调用")
        void userForbidden() throws Exception {
            WeeklyOpportunityUpdateRequest req = nineEmptyRequest();

            mvc.perform(put("/api/invest/weekly-opportunity/tech_vc")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());

            verify(weeklyOpportunityService, never()).update(anyString(), any());
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            WeeklyOpportunityUpdateRequest req = nineEmptyRequest();
            when(weeklyOpportunityService.update(anyString(), any()))
                    .thenReturn(List.of(WeeklyOpportunitySlotDTO.builder().poolType("tech_vc").slotIndex(0).build()));

            mvc.perform(put("/api/invest/weekly-opportunity/tech_vc")
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isOk());

            verify(weeklyOpportunityService).update(anyString(), any());
        }

        @Test
        @DisplayName("ADMIN → 200")
        void adminAllowed() throws Exception {
            WeeklyOpportunityUpdateRequest req = nineEmptyRequest();
            when(weeklyOpportunityService.update(anyString(), any()))
                    .thenReturn(List.of());

            mvc.perform(put("/api/invest/weekly-opportunity/tech_vc")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
        }
    }

    // ══════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════

    private WeeklyOpportunityUpdateRequest nineEmptyRequest() {
        WeeklyOpportunityUpdateRequest req = new WeeklyOpportunityUpdateRequest();
        List<WeeklyOpportunityUpdateRequest.SlotItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            WeeklyOpportunityUpdateRequest.SlotItem item = new WeeklyOpportunityUpdateRequest.SlotItem();
            item.setSlotIndex(i);
            items.add(item);
        }
        req.setSlots(items);
        return req;
    }
}
