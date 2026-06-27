package com.quant.wishpool;

import com.quant.controller.WishPoolController;
import com.quant.dto.wishpool.WishSubmitRequest;
import com.quant.service.WishPoolService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WishPoolController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("WishPoolController")
class WishPoolControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    WishPoolService wishPoolService;

    @Test
    @DisplayName("有效许愿返回 200 和成功消息")
    void submitWishReturnsSuccess() throws Exception {
        doNothing().when(wishPoolService).submitWish(org.mockito.ArgumentMatchers.any(WishSubmitRequest.class));

        mvc.perform(post("/api/wishes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"wish":"希望增加复盘摘要导出，帮我每天整理晨会材料。","page":"/gp/market-recap.html"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已收到许愿，我们会认真评估"));
    }

    @Test
    @DisplayName("空许愿内容返回 400")
    void blankWishReturns400() throws Exception {
        mvc.perform(post("/api/wishes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"wish":"   ","page":"/gp/index.html"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("邮箱格式不正确返回 400")
    void invalidEmailReturns400() throws Exception {
        mvc.perform(post("/api/wishes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"wish":"希望增加每日复盘导出","page":"/gp/index.html","email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("合法邮箱与许愿一并提交成功")
    void submitWithValidEmailReturnsSuccess() throws Exception {
        doNothing().when(wishPoolService).submitWish(org.mockito.ArgumentMatchers.any(WishSubmitRequest.class));

        mvc.perform(post("/api/wishes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"wish":"希望增加每日复盘导出功能","page":"/gp/market-recap.html","email":"user@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已收到许愿，我们会认真评估"));
    }

    @Test
    @DisplayName("服务异常时返回 500")
    void serviceFailureReturns500() throws Exception {
        doThrow(new IllegalStateException("提交失败，请稍后再试"))
                .when(wishPoolService).submitWish(org.mockito.ArgumentMatchers.any(WishSubmitRequest.class));

        mvc.perform(post("/api/wishes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"wish":"希望增加批量导出能力","page":"/gp/index.html"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("提交失败，请稍后再试"));
    }
}
