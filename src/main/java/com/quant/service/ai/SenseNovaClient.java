package com.quant.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SenseNovaClient {

    private final AiProperties props;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ===== Chat Completions =====

    /**
     * 调用 SenseNova chat completions 接口 (OpenAI 兼容).
     * @return assistant 消息的纯文本内容
     */
    public String chatComplete(String systemPrompt, String userPrompt) {
        AiProperties.SenseNova cfg = props.getSensenova();
        if (!cfg.isEnabled() || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException("SenseNova 未启用或未配置 API Key");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(cfg.getTimeoutSeconds() * 1000);
        RestTemplate rest = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptCharset(List.of(StandardCharsets.UTF_8));
        headers.setBearerAuth(cfg.getApiKey());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getChatModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("temperature", 0.5);
        body.put("stream", false);

        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        log.info("SenseNova Chat: model={}, prompt长度={}", cfg.getChatModel(), userPrompt.length());
        String respStr = rest.postForObject(url, entity, String.class);
        if (respStr == null) {
            throw new IllegalStateException("SenseNova Chat 返回为空");
        }
        try {
            JsonNode root = MAPPER.readTree(respStr);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode msg = choices.get(0).path("message").path("content");
                if (!msg.isMissingNode()) {
                    return msg.asText();
                }
            }
            throw new IllegalStateException("SenseNova Chat 响应缺少 content: "
                    + respStr.substring(0, Math.min(300, respStr.length())));
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;
            throw new IllegalStateException("解析 SenseNova Chat 响应失败: " + e.getMessage(), e);
        }
    }

    // ===== Image Generation =====

    /**
     * 调用 SenseNova 图片生成接口.
     * @param prompt 图片描述 (中文,详细越好)
     * @return 生成图片的 URL
     */
    public String generateImage(String prompt) {
        AiProperties.SenseNova cfg = props.getSensenova();
        if (!cfg.isEnabled() || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException("SenseNova 未启用或未配置 API Key");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(cfg.getTimeoutSeconds() * 1000);
        RestTemplate rest = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptCharset(List.of(StandardCharsets.UTF_8));
        headers.setBearerAuth(cfg.getApiKey());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getImageModel());
        body.put("prompt", prompt);
        body.put("size", cfg.getImageSize());
        body.put("n", 1);

        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/images/generations";

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        log.info("SenseNova 图片生成: model={}, prompt长度={}", cfg.getImageModel(), prompt.length());
        String respStr = rest.postForObject(url, entity, String.class);
        if (respStr == null) {
            throw new IllegalStateException("SenseNova 返回为空");
        }
        try {
            JsonNode root = MAPPER.readTree(respStr);
            JsonNode data = root.path("data");
            if (data.isArray() && data.size() > 0) {
                JsonNode first = data.get(0);
                String imageUrl = first.path("url").asText(null);
                if (imageUrl != null && !imageUrl.isBlank()) {
                    return imageUrl;
                }
                String b64 = first.path("b64_json").asText(null);
                if (b64 != null && !b64.isBlank()) {
                    return "data:image/png;base64," + b64;
                }
            }
            throw new IllegalStateException("SenseNova 响应无图片 URL: "
                    + respStr.substring(0, Math.min(300, respStr.length())));
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;
            throw new IllegalStateException("解析 SenseNova 响应失败: " + e.getMessage(), e);
        }
    }
}
