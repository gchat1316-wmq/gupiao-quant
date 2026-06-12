package com.quant.service;

import com.quant.config.NotificationProperties;
import com.quant.dto.wishpool.WishSubmitRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WishPoolService")
class WishPoolServiceTest {

    private NotificationProperties properties;
    private RestTemplate restTemplate;
    private WishPoolService service;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.getWishPool().setEnabled(true);
        properties.getWishPool().setWebhookUrl("https://open.feishu.cn/open-apis/bot/v2/hook/test-hook");
        restTemplate = mock(RestTemplate.class);
        service = new WishPoolService(properties, restTemplate);
    }

    @Test
    @DisplayName("提交许愿时向飞书 webhook 发送文本消息")
    void submitWishPostsFormattedFeishuMessage() {
        WishSubmitRequest request = new WishSubmitRequest();
        request.setWish("希望增加复盘摘要导出，帮我每天整理晨会材料。");
        request.setPage("/gp/market-recap.html");

        when(restTemplate.postForEntity(eq("https://open.feishu.cn/open-apis/bot/v2/hook/test-hook"),
                org.mockito.ArgumentMatchers.any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        service.submitWish(request);

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("https://open.feishu.cn/open-apis/bot/v2/hook/test-hook"),
                entityCaptor.capture(), eq(String.class));

        HttpEntity<?> entity = entityCaptor.getValue();
        assertThat(entity.getHeaders().getContentType().toString()).contains("application/json");
        assertThat(String.valueOf(entity.getBody())).contains("msg_type=text");
        assertThat(String.valueOf(entity.getBody())).contains("投资助手·许愿池");
        assertThat(String.valueOf(entity.getBody())).contains("market-recap.html");
        assertThat(String.valueOf(entity.getBody())).contains("希望增加复盘摘要导出");
    }

    @Test
    @DisplayName("空许愿内容直接拒绝")
    void rejectsBlankWish() {
        WishSubmitRequest request = new WishSubmitRequest();
        request.setWish("   ");

        assertThatThrownBy(() -> service.submitWish(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请输入");
    }

    @Test
    @DisplayName("飞书 webhook 返回非 2xx 时抛出异常")
    void throwsWhenWebhookReturnsFailure() {
        WishSubmitRequest request = new WishSubmitRequest();
        request.setWish("希望增加导出能力");
        request.setPage("/gp/index.html");

        when(restTemplate.postForEntity(eq("https://open.feishu.cn/open-apis/bot/v2/hook/test-hook"),
                org.mockito.ArgumentMatchers.any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("fail", HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> service.submitWish(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("提交失败");
    }
}
