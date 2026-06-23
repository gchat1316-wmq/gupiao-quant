package com.quant.service.industryresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.AiProperties;
import com.quant.service.ai.MiniMaxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 投研链路第二阶段：读研报 / 提炼核心结论
 *
 * 实现方式：当 LLM 启用时，调用 MiniMax (M2.7-highspeed) 让模型以产业研究员身份
 * 基于行业知识 + 关键词生成结构化摘要 JSON；不再依赖本地 Kimi CLI。
 *
 * 兜底：LLM 不可用 / 解析失败时回退到 mockDigest，保证流水线不中断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryReportReader {

    private final IndustryResearchProperties props;
    private final MiniMaxClient miniMaxClient;
    private final AiProperties aiProps;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 读取研报并提炼
     *
     * @param keyword  行业关键词（如 "AI 算力"）
     * @param maxCount 假定阅读的研报数（仅作为 LLM 提示用，不代表真实文件读取）
     * @return 提炼结果：{ "totalRead": N, "summaries": [...], "keyPoints": [...], "sourceDistribution": {...} }
     */
    public Map<String, Object> readAndDigest(String keyword, int maxCount) {
        if (!props.getKimiCli().isEnabled()) {
            log.info("[IndustryReportReader] LLM 模式未启用，回退到 mock 摘要 (keyword={})", keyword);
            return mockDigest(keyword, maxCount);
        }

        if (aiProps == null || aiProps.getMinimax() == null
                || !aiProps.getMinimax().isEnabled()
                || aiProps.getMinimax().getApiKey() == null
                || aiProps.getMinimax().getApiKey().isBlank()) {
            log.warn("[IndustryReportReader] MiniMax 未启用或未配置 Key，回退到 mock");
            return mockDigest(keyword, maxCount);
        }

        try {
            String systemPrompt = "你是一名资深产业研究员，擅长从大量卖方研报和一手资料中提炼结构化结论。";
            String userPrompt = buildUserPrompt(keyword, maxCount);

            log.info("[IndustryReportReader] 调用 MiniMax 摘要 (keyword={}, maxCount={}, prompt 长度={})",
                    keyword, maxCount, userPrompt.length());
            String raw = miniMaxClient.chatComplete(systemPrompt, userPrompt);
            log.debug("[IndustryReportReader] MiniMax 原始返回: {}", raw);

            // 解析 JSON：有些模型会包 ```json ... ```，先剥掉
            String json = stripCodeFence(raw);
            JsonNode node = MAPPER.readTree(json);
            Map<String, Object> result = MAPPER.convertValue(node, Map.class);

            // 兜底字段
            result.putIfAbsent("totalRead", maxCount);
            result.putIfAbsent("keyword", keyword);
            result.putIfAbsent("isMock", false);
            log.info("[IndustryReportReader] MiniMax 摘要成功: totalRead={}, keyPoints={}",
                    result.get("totalRead"),
                    ((List<?>) result.getOrDefault("keyPoints", List.of())).size());
            return result;

        } catch (Exception e) {
            log.warn("[IndustryReportReader] LLM 摘要失败，回退到 mock: {}", e.getMessage());
            return mockDigest(keyword, maxCount);
        }
    }

    private String buildUserPrompt(String keyword, int maxCount) {
        String template = loadPromptTemplate();
        return template
                .replace("{{KEYWORD}}", keyword)
                .replace("{{MAX_COUNT}}", String.valueOf(maxCount));
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource res = new ClassPathResource(props.getKimiCli().getPromptTemplate());
            if (!res.exists()) {
                return defaultPromptTemplate();
            }
            return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return defaultPromptTemplate();
        }
    }

    private String defaultPromptTemplate() {
        return "假设你刚通读了约 {{MAX_COUNT}} 篇关于「{{KEYWORD}}」产业链的卖方研报和一手资料，\n" +
                "请以产业研究员视角提炼结构化结论，**只输出 JSON，不要解释、不要 Markdown 包裹**：\n" +
                "{\n" +
                "  \"totalRead\": " + "{{MAX_COUNT}}" + ",\n" +
                "  \"summaries\": [\n" +
                "    {\"title\": \"研报标题示例\", \"broker\": \"券商名\", \"rating\": \"买入/增持/中性\",\n" +
                "     \"keyPoints\": [\"结论1\", \"结论2\", \"结论3\"]}\n" +
                "  ],\n" +
                "  \"keyPoints\": [\n" +
                "    \"整个产业链的核心结论 1\",\n" +
                "    \"核心结论 2\",\n" +
                "    \"核心结论 3\",\n" +
                "    \"核心结论 4\",\n" +
                "    \"核心结论 5\"\n" +
                "  ],\n" +
                "  \"sourceDistribution\": {\"中金\": 100, \"中信\": 120, \"华泰\": 80, \"招商\": 60, \"其他\": 200}\n" +
                "}\n" +
                "summaries 给 5 条代表性研报即可；keyPoints 必须 5 条；sourceDistribution 是研报数量分布，数字之和约等于 {{MAX_COUNT}}。";
    }

    /** 去掉 ```json ... ``` 包裹 */
    private String stripCodeFence(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) s = s.substring(firstNl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        // 截取第一个 { 到最后一个 }
        int lb = s.indexOf('{');
        int rb = s.lastIndexOf('}');
        if (lb >= 0 && rb > lb) {
            s = s.substring(lb, rb + 1);
        }
        return s;
    }

    /**
     * Mock 摘要（兜底用：LLM 不可用时保证流水线不中断）
     */
    private Map<String, Object> mockDigest(String keyword, int maxCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        int totalRead = Math.min(maxCount, 1_171);
        out.put("totalRead", totalRead);
        out.put("keyword", keyword);

        List<Map<String, Object>> summaries = new ArrayList<>();
        String[] brokers = {"中金", "中信", "华泰", "招商", "国君"};
        String[] ratings = {"买入", "增持", "买入", "强烈推荐", "买入"};
        for (int i = 0; i < 5; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("title", keyword + " 行业研究 · " + (i + 1));
            s.put("broker", brokers[i]);
            s.put("rating", ratings[i]);
            s.put("keyPoints", List.of(
                    keyword + " 板块整体景气度向上",
                    "上游涨价已传导至中游",
                    "龙头估值合理，关注业绩兑现"));
            summaries.add(s);
        }
        out.put("summaries", summaries);
        out.put("keyPoints", List.of(
                keyword + " 产业链上下游验证景气拐点",
                "北美 Capex 持续上修，国内云厂商跟进",
                "PCB/HDI / 光模块 / HBM 三大环节业绩确定",
                "国产替代逻辑兑现中，但估值已部分反映",
                "建议关注龙头确定性 + 二线弹性"));
        out.put("sourceDistribution", Map.of(
                "中金", 142, "中信", 168, "华泰", 121, "招商", 95, "国君", 89, "其他", 556));
        out.put("isMock", true);
        return out;
    }
}