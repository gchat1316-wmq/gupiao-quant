package com.quant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.invest.BatchImportRequest;
import com.quant.dto.invest.BatchImportResultDTO;
import com.quant.dto.invest.OcrImportRequest;
import com.quant.dto.invest.OcrParseResultDTO;
import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolItemDTO;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.SopCheckupDTO;
import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.JwtAuthFilter;
import com.quant.security.JwtTokenProvider;
import com.quant.security.SecurityConfig;
import com.quant.service.InvestPoolRefreshService;
import com.quant.service.InvestPoolSeedService;
import com.quant.service.InvestService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InvestController 角色权限测试。
 *
 * 规则：
 * - 读取接口（GET /pool、GET /sop/checkup）保持公开。
 * - 任何写接口（POST/PUT/PATCH/DELETE /api/invest/pool/**）必须 MANAGER 或 ADMIN。
 * - 调试/重建类接口（monitor/run、seed、refresh）同样必须是 MANAGER/ADMIN。
 *
 * 用真实 JwtTokenProvider 生成 token → JwtAuthFilter 走完解析流程，
 * 模拟前端带 Authorization: Bearer &lt;token&gt; 的请求，验证 @PreAuthorize 拦截效果。
 */
@WebMvcTest(controllers = InvestController.class,
        properties = {
                "app.jwt.secret=test-secret-key-at-least-32-chars-long-for-hs256!",
                "app.sms.code-expire-minutes=5",
                "app.sms.cooldown-seconds=60"
        })
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@DisplayName("InvestController 角色权限")
class InvestControllerAuthTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtTokenProvider tokenProvider;

    @MockBean private InvestService investService;
    @MockBean private OcrPoolImportService ocrService;
    @MockBean private PriceMonitorService priceMonitorService;
    @MockBean private InvestPoolSeedService poolSeedService;
    @MockBean private InvestPoolRefreshService poolRefreshService;
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
    @DisplayName("GET /api/invest/pool")
    class PoolRead {

        @Test
        @DisplayName("未登录 → 200（公开）")
        void anonymousCanRead() throws Exception {
            when(investService.listPool()).thenReturn(List.of(PoolItemDTO.builder().id(1).stockCode("002371").stockName("北方华创").build()));
            mvc.perform(get("/api/invest/pool"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("USER → 200（公开）")
        void userCanRead() throws Exception {
            when(investService.listPool()).thenReturn(List.of());
            mvc.perform(get("/api/invest/pool").header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /api/invest/sop/checkup")
    class SopCheckupRead {

        @Test
        @DisplayName("未登录 → 200（公开）")
        void anonymousCanCheck() throws Exception {
            when(investService.sopCheckup("600519")).thenReturn(SopCheckupDTO.builder().matched(true).build());
            mvc.perform(get("/api/invest/sop/checkup").param("keyword", "600519"))
                    .andExpect(status().isOk());
        }
    }

    // ══════════════════════════════════════════════════
    // 写接口 — 必须 MANAGER 或 ADMIN
    // ══════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/invest/pool（加入股票池）")
    class AddPool {

        @Test
        @DisplayName("USER → 403")
        void userForbidden() throws Exception {
            PoolSaveRequest req = new PoolSaveRequest();
            req.setKeyword("002371");
            req.setPoolType("quality");
            req.setStatus("watching");

            mvc.perform(post("/api/invest/pool")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());

            verify(investService, never()).addToPool(any());
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            PoolSaveRequest req = new PoolSaveRequest();
            req.setKeyword("002371");
            req.setPoolType("quality");
            req.setStatus("watching");
            when(investService.addToPool(any())).thenReturn(PoolItemDTO.builder().build());

            mvc.perform(post("/api/invest/pool")
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isOk());

            verify(investService).addToPool(any());
        }

        @Test
        @DisplayName("ADMIN → 200")
        void adminAllowed() throws Exception {
            PoolSaveRequest req = new PoolSaveRequest();
            req.setKeyword("002371");
            when(investService.addToPool(any())).thenReturn(PoolItemDTO.builder().build());

            mvc.perform(post("/api/invest/pool")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("PUT /api/invest/pool/{id}")
    class UpdatePool {

        @Test
        @DisplayName("USER → 403")
        void userForbidden() throws Exception {
            PoolSaveRequest req = new PoolSaveRequest();
            req.setKeyword("002371");
            req.setPoolType("quality");

            mvc.perform(put("/api/invest/pool/100")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());

            verify(investService, never()).updatePool(anyInt(), any());
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            PoolSaveRequest req = new PoolSaveRequest();
            req.setKeyword("002371");
            when(investService.updatePool(anyInt(), any())).thenReturn(PoolItemDTO.builder().build());

            mvc.perform(put("/api/invest/pool/100")
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("PATCH /api/invest/pool/{id}/field")
    class PatchField {

        @Test
        @DisplayName("USER → 403")
        void userForbidden() throws Exception {
            PoolFieldUpdateRequest req = new PoolFieldUpdateRequest();
            req.setField("memo");
            req.setValue("修改备注");

            mvc.perform(patch("/api/invest/pool/100/field")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());

            verify(investService, never()).updateField(anyInt(), any());
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            PoolFieldUpdateRequest req = new PoolFieldUpdateRequest();
            req.setField("memo");
            req.setValue("修改备注");
            when(investService.updateField(anyInt(), any())).thenReturn(PoolItemDTO.builder().build());

            mvc.perform(patch("/api/invest/pool/100/field")
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("DELETE /api/invest/pool/{id}")
    class RemovePool {

        @Test
        @DisplayName("USER → 403")
        void userForbidden() throws Exception {
            mvc.perform(delete("/api/invest/pool/100")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());

            verify(investService, never()).removeFromPool(anyInt());
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            doNothing().when(investService).removeFromPool(anyInt());

            mvc.perform(delete("/api/invest/pool/100")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isOk());

            verify(investService).removeFromPool(anyInt());
        }
    }

    @Nested
    @DisplayName("POST /api/invest/pool/import-image")
    class ImportImage {

        @Test
        @DisplayName("USER → 403")
        void userForbidden() throws Exception {
            OcrImportRequest req = new OcrImportRequest();
            req.setImageBase64("data:image/png;base64,test");
            req.setDefaultPoolType("tech_vc");

            mvc.perform(post("/api/invest/pool/import-image")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());

            verify(ocrService, never()).parseImage(any());
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            OcrImportRequest req = new OcrImportRequest();
            req.setImageBase64("data:image/png;base64,test");
            when(ocrService.parseImage(any())).thenReturn(OcrParseResultDTO.builder().build());

            mvc.perform(post("/api/invest/pool/import-image")
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST /api/invest/pool/batch-import")
    class BatchImport {

        @Test
        @DisplayName("USER → 403")
        void userForbidden() throws Exception {
            BatchImportRequest req = new BatchImportRequest();
            req.setItems(List.of());

            mvc.perform(post("/api/invest/pool/batch-import")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());

            verify(ocrService, never()).batchImport(any());
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            BatchImportRequest req = new BatchImportRequest();
            req.setItems(List.of());
            when(ocrService.batchImport(any())).thenReturn(BatchImportResultDTO.builder().build());

            mvc.perform(post("/api/invest/pool/batch-import")
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
        }
    }

    // ══════════════════════════════════════════════════
    // 调试 / 重建类接口
    // ══════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/invest/pool/monitor/run")
    class MonitorRun {

        @Test
        @DisplayName("USER → 403")
        void userForbidden() throws Exception {
            mvc.perform(post("/api/invest/pool/monitor/run")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
            verify(priceMonitorService, never()).monitorPrices();
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            mvc.perform(post("/api/invest/pool/monitor/run")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST /api/invest/pool/seed/tech-vc-screenshot")
    class SeedPool {

        @Test
        @DisplayName("USER → 403")
        void userForbidden() throws Exception {
            mvc.perform(post("/api/invest/pool/seed/tech-vc-screenshot")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
            verify(poolSeedService, never()).replaceTechVcWithScreenshotPool();
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            when(poolSeedService.replaceTechVcWithScreenshotPool()).thenReturn(5);
            mvc.perform(post("/api/invest/pool/seed/tech-vc-screenshot")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST /api/invest/pool/refresh")
    class RefreshPool {

        @Test
        @DisplayName("USER → 403")
        void userForbidden() throws Exception {
            mvc.perform(post("/api/invest/pool/refresh")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
            verify(poolRefreshService, never()).refreshAllPoolSnapshots();
        }

        @Test
        @DisplayName("MANAGER → 200")
        void managerAllowed() throws Exception {
            when(poolRefreshService.refreshAllPoolSnapshots()).thenReturn(3);
            mvc.perform(post("/api/invest/pool/refresh")
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isOk());
        }
    }
}
