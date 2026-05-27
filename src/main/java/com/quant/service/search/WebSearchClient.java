package com.quant.service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.AiProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchClient {

    private final AiProperties props;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public boolean isEnabled() {
        AiProperties.Tavily cfg = props.getTavily();
        return cfg.isEnabled() && cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    /** 单次搜索，失败返回空列表，调用方决定是否退化。 */
    public List<SearchResult> search(String query) {
        if (!isEnabled()) return List.of();
        AiProperties.Tavily cfg = props.getTavily();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8_000);
        factory.setReadTimeout(cfg.getTimeoutSeconds() * 1000);
        RestTemplate rest = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptCharset(List.of(StandardCharsets.UTF_8));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key", cfg.getApiKey());
        body.put("query", query);
        body.put("search_depth", "basic");
        body.put("include_answer", true);
        body.put("max_results", cfg.getMaxResults());

        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/search";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            log.info("Tavily 搜索: {}", query);
            String respStr = rest.postForObject(url, entity, String.class);
            if (respStr == null) return List.of();
            JsonNode root = MAPPER.readTree(respStr);
            String answer = root.path("answer").asText("");
            List<SearchResult> out = new ArrayList<>();
            if (!answer.isBlank()) {
                out.add(new SearchResult("[summary]", "", answer));
            }
            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    out.add(new SearchResult(
                            r.path("title").asText(""),
                            r.path("url").asText(""),
                            r.path("content").asText("")));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Tavily 搜索失败: {} - {}", query, e.getMessage());
            return List.of();
        }
    }

    @Getter
    public static class SearchResult {
        private final String title;
        private final String url;
        private final String content;

        public SearchResult(String title, String url, String content) {
            this.title = title;
            this.url = url;
            this.content = content;
        }

        public String toLine() {
            String c = content == null ? "" : content.replaceAll("\\s+", " ");
            if (c.length() > 500) c = c.substring(0, 500) + "…";
            return "- " + title + ": " + c;
        }
    }
}
