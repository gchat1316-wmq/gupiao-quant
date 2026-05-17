package com.quant.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class MiniMaxClient {

    private final AiProperties props;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 调用 MiniMax 的 Anthropic 兼容 Messages 接口.
     * @return assistant 消息的纯文本内容
     */
    public String chatComplete(String systemPrompt, String userPrompt) {
        AiProperties.MiniMax cfg = props.getMinimax();
        if (!cfg.isEnabled() || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException("MiniMax 未启用或未配置 API Key");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(cfg.getTimeoutSeconds() * 1000);
        RestTemplate rest = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptCharset(List.of(StandardCharsets.UTF_8));
        headers.setBearerAuth(cfg.getApiKey());

        // Anthropic Messages API 格式
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("max_tokens", 4096);
        body.put("system", systemPrompt);
        body.put("messages", List.of(
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("temperature", 0.4);
        body.put("stream", false);

        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/messages";

        org.springframework.http.HttpEntity<Map<String, Object>> entity =
                new org.springframework.http.HttpEntity<>(body, headers);

        log.info("MiniMax 调用: model={}, prompt长度={}", cfg.getModel(), userPrompt.length());
        String respStr = rest.postForObject(url, entity, String.class);
        if (respStr == null) {
            throw new IllegalStateException("MiniMax 返回为空");
        }
        try {
            JsonNode root = MAPPER.readTree(respStr);

            // 检查错误响应
            JsonNode baseResp = root.path("base_resp");
            if (!baseResp.isMissingNode() && baseResp.has("status_code")
                    && baseResp.get("status_code").asInt(-1) != 0) {
                String errMsg = baseResp.path("status_msg").asText("未知错误");
                throw new IllegalStateException("MiniMax API 错误: " + errMsg);
            }

            // Anthropic 格式: content 数组, 取 type=="text" 的项
            JsonNode contentArr = root.path("content");
            if (contentArr.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : contentArr) {
                    if ("text".equals(item.path("type").asText())) {
                        sb.append(item.path("text").asText());
                    }
                }
                if (!sb.isEmpty()) {
                    return sb.toString();
                }
            }

            // 兜底: OpenAI 兼容格式 choices[0].message.content
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode msg = choices.get(0).path("message").path("content");
                if (!msg.isMissingNode()) {
                    return msg.asText();
                }
            }

            throw new IllegalStateException("MiniMax 响应缺少 content/choices: " + respStr);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 MiniMax 响应失败: " + e.getMessage(), e);
        }
    }
}
