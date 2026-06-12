package com.quant.service;

import com.quant.config.NotificationProperties;
import com.quant.dto.wishpool.WishSubmitRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class WishPoolService {

    private final NotificationProperties properties;
    private final RestTemplate restTemplate;

    @Autowired
    public WishPoolService(NotificationProperties properties, RestTemplateBuilder restTemplateBuilder) {
        this(properties, restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(properties.getWishPool().getTimeoutSeconds()))
                .build());
    }

    WishPoolService(NotificationProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    public void submitWish(WishSubmitRequest request) {
        String wish = request == null || request.getWish() == null ? "" : request.getWish().trim();
        if (wish.isEmpty()) {
            throw new IllegalArgumentException("请输入想要的能力或需求");
        }

        NotificationProperties.WishPool cfg = properties.getWishPool();
        if (!cfg.isEnabled() || cfg.getWebhookUrl() == null || cfg.getWebhookUrl().isBlank()) {
            throw new IllegalStateException("许愿池暂未开放");
        }

        String page = request.getPage() == null || request.getPage().isBlank() ? "未知页面" : request.getPage().trim();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "text");
        payload.put("content", Map.of(
                "text", "【投资助手·许愿池】\n页面：" + page + "\n需求：" + wish
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    cfg.getWebhookUrl(),
                    new HttpEntity<>(payload, headers),
                    String.class
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("提交失败，请稍后再试");
            }
            log.info("wish pool submitted: page={}, wish={}", page, wish);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("wish pool submit failed: {}", e.getMessage());
            throw new IllegalStateException("提交失败，请稍后再试");
        }
    }
}
