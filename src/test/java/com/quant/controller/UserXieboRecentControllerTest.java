package com.quant.controller;

import com.quant.dto.xiebo.UserSubscriptionDto;
import com.quant.entity.User;
import com.quant.repository.UserRepository;
import com.quant.security.JwtAuthFilter;
import com.quant.security.JwtTokenProvider;
import com.quant.security.SecurityConfig;
import com.quant.service.XieboRecentSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserXieboRecentController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
class UserXieboRecentControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtTokenProvider tokenProvider;

    @MockBean
    XieboRecentSubscriptionService subscriptionService;

    @MockBean
    UserRepository userRepository;

    private String userToken;

    @BeforeEach
    void setUp() {
        userToken = tokenProvider.generate(7L, "USER");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L)));
    }

    private static User user(Long id) {
        User u = new User();
        u.setId(id);
        u.setPhone("13800138000");
        u.setUsername("测试用户" + id);
        u.setRole(User.Role.USER);
        u.setDisabled(false);
        return u;
    }

    @Test
    void list_returnsServiceResult() throws Exception {
        when(subscriptionService.listByUser(7L)).thenReturn(List.of());

        mvc.perform(get("/api/me/recent/subscriptions")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        verify(subscriptionService).listByUser(7L);
    }

    @Test
    void upsert_callsService() throws Exception {
        when(subscriptionService.upsert(eq(7L), eq("600519"), any()))
                .thenReturn(UserSubscriptionDto.builder()
                        .id(42L).stockCode("600519").enabled(true).build());

        mvc.perform(put("/api/me/recent/subscriptions/600519")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"status\":\"关注\",\"priceBuy\":1850}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.subscriptionId").value(42))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void resetAlerts_callsService() throws Exception {
        mvc.perform(post("/api/me/recent/subscriptions/600519/reset-alerts")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        verify(subscriptionService).resetAlerts(7L, "600519");
    }

    @Test
    void setSckey_updatesUser() throws Exception {
        User u = user(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(u));

        mvc.perform(put("/api/me/serverchan-key")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serverchanSendKey\":\"SCT_NEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        verify(userRepository).save(u);
    }
}
