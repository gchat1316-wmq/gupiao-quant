package com.quant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.PositionFillRequest;
import com.quant.dto.techai.PositionFillDTO;
import com.quant.dto.techai.TechAiAlertDTO;
import com.quant.dto.techai.TechAiPoolItemDTO;
import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.JwtAuthFilter;
import com.quant.security.JwtTokenProvider;
import com.quant.security.SecurityConfig;
import com.quant.service.PotentialService;
import com.quant.service.TechAiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PotentialController + TechAiController 写权限测试。
 *
 * 规则：POST /pool, PATCH /pool/{id}/field, DELETE /pool/{id},
 *       POST /pool/{id}/fill, DELETE /pool/{id}/fills/{fillId},
 *       POST /monitor/run 全部要求 MANAGER 或 ADMIN。
 *
 * 读接口（GET /pool, GET /pool/{id}/fills, GET /alerts）保持 authenticated()。
 */
@WebMvcTest(controllers = {PotentialController.class, TechAiController.class},
        properties = {
                "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hs256!"
        })
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@DisplayName("Potential/TechAi 写权限 (MANAGER/ADMIN)")
class PoolWriteAuthTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtTokenProvider tokenProvider;

    @MockBean private PotentialService potentialService;
    @MockBean private TechAiService techAiService;
    @MockBean private UserRepository userRepository;

    private String adminToken;
    private String managerToken;
    private String heavyUserToken;

    @BeforeEach
    void setUp() {
        adminToken = tokenProvider.generate(1L, "ADMIN");
        managerToken = tokenProvider.generate(2L, "MANAGER");
        heavyUserToken = tokenProvider.generate(3L, "USER");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, User.Role.ADMIN)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, User.Role.MANAGER)));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3L, User.Role.USER, true)));
    }

    private static User user(Long id, User.Role role) {
        return user(id, role, false);
    }

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

    private TechAiPoolItemDTO sampleItem() {
        return null; // 内容不重要, 只验证鉴权码
    }

    private PoolSaveRequest saveReq() {
        return new PoolSaveRequest();
    }

    private PoolFieldUpdateRequest fieldReq() {
        PoolFieldUpdateRequest r = new PoolFieldUpdateRequest();
        r.setField("memo");
        r.setValue("新备注");
        return r;
    }

    private PositionFillRequest fillReq() {
        PositionFillRequest r = new PositionFillRequest();
        r.setPrice(new BigDecimal("125.50"));
        r.setLots(new BigDecimal("100"));
        return r;
    }

    @Test
    @DisplayName("POST /api/potential/pool - ADMIN 可以")
    void adminCanAddPotential() throws Exception {
        when(potentialService.addToPool(any())).thenReturn(sampleItem());
        mvc.perform(post("/api/potential/pool")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(saveReq())))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("POST /api/potential/pool - MANAGER 可以")
    void managerCanAddPotential() throws Exception {
        when(potentialService.addToPool(any())).thenReturn(sampleItem());
        mvc.perform(post("/api/potential/pool")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(saveReq())))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("POST /api/potential/pool - 重度 USER（开启 SMS）拒绝 403")
    void heavyUserCannotAddPotential() throws Exception {
        mvc.perform(post("/api/potential/pool")
                        .header("Authorization", "Bearer " + heavyUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(saveReq())))
                .andExpect(status().isForbidden());
        verify(potentialService, never()).addToPool(any());
    }

    @Test
    @DisplayName("PATCH /api/potential/pool/{id}/field - USER 拒绝")
    void userCannotPatchPotentialField() throws Exception {
        mvc.perform(patch("/api/potential/pool/7/field")
                        .header("Authorization", "Bearer " + heavyUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(fieldReq())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/potential/pool/{id} - MANAGER 可以")
    void managerCanRemovePotential() throws Exception {
        mvc.perform(delete("/api/potential/pool/7")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("DELETE /api/potential/pool/{id} - USER 拒绝")
    void userCannotRemovePotential() throws Exception {
        mvc.perform(delete("/api/potential/pool/7")
                        .header("Authorization", "Bearer " + heavyUserToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/potential/monitor/run - USER 拒绝（监控只能 MANAGER/ADMIN 触发）")
    void userCannotRunPotentialMonitor() throws Exception {
        mvc.perform(post("/api/potential/monitor/run")
                        .header("Authorization", "Bearer " + heavyUserToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/potential/pool - USER 可以读")
    void userCanListPotentialPool() throws Exception {
        when(potentialService.listPool()).thenReturn(List.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/potential/pool")
                        .header("Authorization", "Bearer " + heavyUserToken))
                .andExpect(status().isOk());
    }

    // ===== TechAiController =====

    @Test
    @DisplayName("POST /api/tech-ai/pool - MANAGER 可以")
    void managerCanAddTechAi() throws Exception {
        when(techAiService.addToPool(any())).thenReturn(sampleItem());
        mvc.perform(post("/api/tech-ai/pool")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(saveReq())))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("POST /api/tech-ai/pool - USER 拒绝")
    void userCannotAddTechAi() throws Exception {
        mvc.perform(post("/api/tech-ai/pool")
                        .header("Authorization", "Bearer " + heavyUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(saveReq())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/tech-ai/monitor/run - USER 拒绝")
    void userCannotRunTechAiMonitor() throws Exception {
        mvc.perform(post("/api/tech-ai/monitor/run")
                        .header("Authorization", "Bearer " + heavyUserToken))
                .andExpect(status().isForbidden());
    }
}
