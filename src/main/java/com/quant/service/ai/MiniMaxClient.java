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
    private final AiCircuitBreaker circuitBreaker;

    private static final String PROVIDER_NAME = "minimax";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 调用 MiniMax 的 Anthropic 兼容 Messages 接口（纯文本）。
     */
    public String chatComplete(String systemPrompt, String userPrompt) {
        return chatCompleteInternal(systemPrompt, userPrompt, null, null);
    }

    /**
     * 调用 MiniMax 视觉/多模态接口。
     *
     * @param systemPrompt    系统提示
     * @param userPrompt      用户文本提示
     * @param imageBase64     图片的 base64（可带 data:image/...;base64, 前缀，会自动剥离）
     * @param imageMediaType  图片 MIME，如 image/png；为空时按 base64 前缀推断，否则默认 image/jpeg
     */
    public String chatCompleteVision(String systemPrompt, String userPrompt,
                                      String imageBase64, String imageMediaType) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new IllegalArgumentException("图片 base64 不能为空");
        }
        String pureBase64 = imageBase64;
        String mediaType = imageMediaType;
        if (pureBase64.startsWith("data:")) {
            int comma = pureBase64.indexOf(',');
            int semi = pureBase64.indexOf(';');
            if (semi > 5 && (mediaType == null || mediaType.isBlank())) {
                mediaType = pureBase64.substring(5, semi);
            }
            if (comma > 0) {
                pureBase64 = pureBase64.substring(comma + 1);
            }
        }
        if (mediaType == null || mediaType.isBlank()) mediaType = "image/jpeg";
        return chatCompleteInternal(systemPrompt, userPrompt, pureBase64, mediaType);
    }

    private String chatCompleteInternal(String systemPrompt, String userPrompt,
                                         String imageBase64, String imageMediaType) {
        // 熔断检查：避免 401 key 失效时调 90s 超时白等
        if (circuitBreaker.isOpen(PROVIDER_NAME)) {
            throw new IllegalStateException("MiniMax 熔断中 (api-key 可能失效)，跳过调用");
        }

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
        headers.set("X-Api-Key", cfg.getApiKey());

        // 选择模型：视觉调用优先用 visionModel
        String model = cfg.getModel();
        if (imageBase64 != null && cfg.getVisionModel() != null && !cfg.getVisionModel().isBlank()) {
            model = cfg.getVisionModel();
        }

        // 构建 user content：文本 / 多模态混合
        Object userContent;
        if (imageBase64 == null) {
            userContent = userPrompt;
        } else {
            Map<String, Object> imageBlock = new LinkedHashMap<>();
            imageBlock.put("type", "image");
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("type", "base64");
            source.put("media_type", imageMediaType);
            source.put("data", imageBase64);
            imageBlock.put("source", source);
            userContent = List.of(
                    imageBlock,
                    Map.of("type", "text", "text", userPrompt)
            );
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 4096);
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", userContent)));
        body.put("temperature", 0.2);
        body.put("stream", false);

        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/messages";

        org.springframework.http.HttpEntity<Map<String, Object>> entity =
                new org.springframework.http.HttpEntity<>(body, headers);

        log.info("MiniMax 调用: model={}, vision={}, prompt长度={}", model, imageBase64 != null, userPrompt.length());
        String respStr;
        try {
            respStr = rest.postForObject(url, entity, String.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            String errBody = e.getResponseBodyAsString();
            if (code == 401 || code == 403) {
                // 鉴权失败：上熔断器
                circuitBreaker.recordAuthFailure(PROVIDER_NAME);
                log.error("MiniMax 鉴权失败 [{}] body={}", code, errBody);
            }
            throw new IllegalStateException("MiniMax HTTP " + code + ": " + errBody, e);
        }
        if (respStr == null) {
            throw new IllegalStateException("MiniMax 返回为空");
        }
        // 成功调用（这里有可能 response body 里仍含 error，但 base_resp 路径会处理）
        circuitBreaker.recordSuccess(PROVIDER_NAME);
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
