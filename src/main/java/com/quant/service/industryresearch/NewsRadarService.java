package com.quant.service.industryresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * News Radar：24h 抓取行业相关新闻
 * 对应投研链路第三阶段：追热点
 *
 * 数据源：Tavily Search API（已在 application.yml 中配置）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsRadarService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final IndustryResearchProperties props;
    private final com.quant.config.AiProperties aiProps;
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 抓取 24h 新闻
     *
     * @param keyword 行业关键词
     * @return { "newsCount": N, "items": [...], "topKeywords": [...] }
     */
    public Map<String, Object> fetch24h(String keyword) {
        if (!props.getNewsRadar().isEnabled() || !aiProps.getTavily().isEnabled()) {
            log.info("[NewsRadar] 未启用 Tavily，回退到 mock 新闻");
            return mockNews(keyword);
        }

        try {
            String url = aiProps.getTavily().getBaseUrl() + "/search";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + aiProps.getTavily().getApiKey());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", keyword + " 行业 最新 24h");
            body.put("max_results", props.getNewsRadar().getMaxResults());
            body.put("topic", "news");
            body.put("days", 1);
            body.put("include_raw_content", false);

            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
            @SuppressWarnings("rawtypes")
            Map resp = restTemplate.postForObject(url, req, Map.class);

            if (resp == null) return mockNews(keyword);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.getOrDefault("results", List.of());
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> r : results) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", r.get("title"));
                item.put("url", r.get("url"));
                item.put("content", truncate(String.valueOf(r.getOrDefault("content", "")), 240));
                item.put("source", extractHost(String.valueOf(r.get("url"))));
                item.put("publishedAt", r.getOrDefault("published_date", Instant.now().toString()));
                items.add(item);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("newsCount", items.size());
            out.put("items", items);
            out.put("keyword", keyword);
            out.put("fetchedAt", Instant.now().toString());
            out.put("topKeywords", extractTopKeywords(items));
            return out;

        } catch (Exception e) {
            log.warn("[NewsRadar] Tavily 调用失败，回退 mock: {}", e.getMessage());
            return mockNews(keyword);
        }
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String extractHost(String url) {
        try {
            int idx1 = url.indexOf("://");
            if (idx1 < 0) return url;
            int idx2 = url.indexOf("/", idx1 + 3);
            return idx2 < 0 ? url.substring(idx1 + 3) : url.substring(idx1 + 3, idx2);
        } catch (Exception e) {
            return url;
        }
    }

    private List<Map<String, Object>> extractTopKeywords(List<Map<String, Object>> items) {
        Map<String, Integer> cnt = new HashMap<>();
        for (Map<String, Object> it : items) {
            String title = String.valueOf(it.getOrDefault("title", ""));
            // 简单分词：按空格 + 标点
            String[] tokens = title.split("[\\s,，。.;；:：!！?？]+");
            for (String t : tokens) {
                if (t.length() >= 2) cnt.merge(t, 1, Integer::sum);
            }
        }
        return cnt.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(10)
                .map(e -> Map.<String, Object>of("keyword", e.getKey(), "count", e.getValue()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Mock 新闻（演示用）
     */
    private Map<String, Object> mockNews(String keyword) {
        List<Map<String, Object>> items = List.of(
                Map.of("title", keyword + " 龙头 Q3 业绩超预期，机构上调目标价", "source", "财联社",
                        "publishedAt", Instant.now().minus(1, ChronoUnit.HOURS).toString(),
                        "content", "..."),
                Map.of("title", "北美云厂商 Capex 再上修，" + keyword + " 全链受益", "source", "Reuters",
                        "publishedAt", Instant.now().minus(2, ChronoUnit.HOURS).toString(),
                        "content", "..."),
                Map.of("title", keyword + " 上游材料涨价已传导，Q4 毛利率有望修复", "source", "证券时报",
                        "publishedAt", Instant.now().minus(3, ChronoUnit.HOURS).toString(),
                        "content", "..."),
                Map.of("title", "国产替代加速：" + keyword + " 关键设备国产化率突破 30%", "source", "上证报",
                        "publishedAt", Instant.now().minus(5, ChronoUnit.HOURS).toString(),
                        "content", "..."),
                Map.of("title", "海外巨头新动作，" + keyword + " 竞争格局或生变", "source", "FT",
                        "publishedAt", Instant.now().minus(8, ChronoUnit.HOURS).toString(),
                        "content", "..."));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("newsCount", items.size());
        out.put("items", items);
        out.put("keyword", keyword);
        out.put("fetchedAt", Instant.now().toString());
        out.put("topKeywords", List.of(
                Map.of("keyword", "Capex", "count", 8),
                Map.of("keyword", keyword, "count", 6),
                Map.of("keyword", "涨价", "count", 4)));
        out.put("isMock", true);
        return out;
    }
}