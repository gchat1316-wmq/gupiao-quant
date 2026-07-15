package com.quant.service;

import com.quant.config.NotificationProperties;
import com.quant.entity.WishPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步推飞书 webhook — 拆出来主要是为了走 Spring @Async 代理（同 Bean 内自调失效）。
 * 新留言入库后由 {@link WishPoolService} 异步触发,失败仅记日志,不阻塞用户提交。
 */
@Slf4j
@Component
public class WishPoolNotifier {

    private final NotificationProperties properties;
    private final RestTemplate restTemplate;

    @Autowired
    public WishPoolNotifier(NotificationProperties properties, RestTemplateBuilder restTemplateBuilder) {
        this(properties, restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(properties.getWishPool().getTimeoutSeconds()))
                .build());
    }

    WishPoolNotifier(NotificationProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Async
    public void notifyNewWish(WishPool wish) {
        NotificationProperties.WishPool cfg = properties.getWishPool();
        if (!cfg.isEnabled() || cfg.getWebhookUrl() == null || cfg.getWebhookUrl().isBlank()) {
            log.debug("wish pool notifier disabled, skip (id={})", wish.getId());
            return;
        }

        StringBuilder text = new StringBuilder("【投资助手·许愿池】新留言 #")
                .append(wish.getId())
                .append("\n来源：").append(wish.getPage() == null ? "未知页面" : wish.getPage())
                .append("\n愿望：").append(wish.getWish());
        if (wish.getEmail() != null && !wish.getEmail().isBlank()) {
            text.append("\n联系邮箱：").append(wish.getEmail());
        }
        if (wish.getIp() != null && !wish.getIp().isBlank()) {
            text.append("\nIP：").append(wish.getIp());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "text");
        payload.put("content", Map.of("text", text.toString()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    cfg.getWebhookUrl(),
                    new HttpEntity<>(payload, headers),
                    String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("wish pool notify ok: id={}", wish.getId());
            } else {
                log.warn("wish pool notify non-2xx: id={} status={}",
                        wish.getId(), response.getStatusCode());
            }
        } catch (Exception e) {
            // 异步通道失败仅记日志,不抛出(用户已经看到提交成功)
            log.warn("wish pool notify failed: id={} err={}", wish.getId(), e.getMessage());
        }
    }
}
