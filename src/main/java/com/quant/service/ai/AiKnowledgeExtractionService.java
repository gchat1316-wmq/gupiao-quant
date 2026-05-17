package com.quant.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiKnowledgeExtractionService {

    private final AiProperties props;
    private final MiniMaxClient miniMaxClient;
    private final SenseNovaClient senseNovaClient;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            你是一位资深的知识图谱构建专家,擅长把一本书或一份学习资料,提炼为结构化的知识体系树。
            请始终用简体中文回答。请只输出严格的 JSON,不要输出任何额外文字、解释或 Markdown 包裹。
            JSON 必须满足以下结构:
            {
              "title": "《资料标题》核心知识体系",
              "summary": "对全书或资料的总览(2-4 句话)",
              "definition": "全书最关键的一个核心概念定义(可为空字符串)",
              "children": [
                {
                  "title": "一级章节标题",
                  "summary": "本章总览",
                  "definition": "本章核心定义(可空)",
                  "children": [
                    {
                      "title": "二级小节标题",
                      "summary": "本小节总览",
                      "definition": "本小节核心定义(可空)",
                      "children": [
                        { "title": "叶子知识点标题", "summary": "本知识点 1-3 句话的精炼解释", "definition": "", "children": [] }
                      ]
                    }
                  ]
                }
              ]
            }
            约束:
            - 一级章节 4-6 个;二级小节每章 2-4 个;叶子知识点每个二级小节 2-4 个。
            - 标题精炼,小于 16 个汉字。
            - summary 简洁、可读、不能为空。
            - 严禁输出多余文本,严禁输出 ```json ``` 这种代码围栏。
            """;

    public ExtractedNode extract(String courseTitle, String extractedText) {
        AiProperties.SenseNova snova = props.getSensenova();
        AiProperties.MiniMax mmax = props.getMinimax();

        // 优先 SenseNova, 其次 MiniMax
        if (snova.isEnabled() && snova.getApiKey() != null && !snova.getApiKey().isBlank()) {
            try {
                String userPrompt = buildUserPrompt(courseTitle, extractedText, 8000);
                String raw = senseNovaClient.chatComplete(SYSTEM_PROMPT, userPrompt);
                ExtractedNode tree = parseJson(raw);
                if (tree != null) {
                    log.info("SenseNova 知识树生成成功: 根节点={}, 子节点数={}", tree.getTitle(),
                            tree.getChildren() == null ? 0 : tree.getChildren().size());
                    return tree;
                }
                log.warn("SenseNova 返回内容无法解析为知识树,尝试 MiniMax");
            } catch (Exception e) {
                log.warn("SenseNova 调用失败: {} -> 尝试 MiniMax", e.getMessage());
            }
        }

        if (mmax.isEnabled() && mmax.getApiKey() != null && !mmax.getApiKey().isBlank()) {
            try {
                String userPrompt = buildUserPrompt(courseTitle, extractedText, mmax.getMaxInputChars());
                String raw = miniMaxClient.chatComplete(SYSTEM_PROMPT, userPrompt);
                ExtractedNode tree = parseJson(raw);
                if (tree != null) {
                    log.info("MiniMax 知识树生成成功: 根节点={}, 子节点数={}", tree.getTitle(),
                            tree.getChildren() == null ? 0 : tree.getChildren().size());
                    return tree;
                }
                log.warn("MiniMax 返回内容无法解析为知识树,回退到 mock");
            } catch (Exception e) {
                log.warn("MiniMax 调用失败: {} -> 回退到 mock", e.getMessage());
                if (!props.isFallbackToMock()) {
                    throw new IllegalStateException("AI 调用失败: " + e.getMessage(), e);
                }
            }
        } else if (!snova.isEnabled()) {
            log.info("所有 AI 均未启用,使用 mock 数据");
        }

        // 回退到 mock
        if (MockBookData.matches(courseTitle)) {
            return MockBookData.investingSimpleThings();
        }
        return MockBookData.genericFallback(courseTitle);
    }

    private String buildUserPrompt(String title, String text, int maxChars) {
        String t = text == null ? "" : text;
        if (t.length() > maxChars) {
            t = t.substring(0, maxChars);
        }
        return "请基于以下资料,生成《" + (title == null ? "未命名资料" : title) + "》的核心知识体系 JSON。\n" +
                "资料内容(可能为截断):\n```\n" + t + "\n```";
    }

    private ExtractedNode parseJson(String raw) {
        if (raw == null) return null;
        String cleaned = stripFences(raw).trim();
        // 尽量找到第一个 '{' 与最后一个 '}'
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        String json = cleaned.substring(start, end + 1);
        try {
            JsonNode root = MAPPER.readTree(json);
            return toNode(root);
        } catch (Exception e) {
            log.warn("解析 AI JSON 失败: {} | 原文: {}", e.getMessage(), shortOf(json));
            return null;
        }
    }

    private String stripFences(String s) {
        Matcher m = Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL).matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        return s;
    }

    private String shortOf(String s) {
        if (s == null) return null;
        return s.length() <= 240 ? s : s.substring(0, 240) + "...";
    }

    private ExtractedNode toNode(JsonNode n) {
        if (n == null || n.isNull()) return null;
        ExtractedNode node = new ExtractedNode();
        node.setTitle(text(n, "title"));
        node.setSummary(text(n, "summary"));
        node.setDefinition(text(n, "definition"));
        List<ExtractedNode> children = new ArrayList<>();
        JsonNode ch = n.get("children");
        if (ch != null && ch.isArray()) {
            Iterator<JsonNode> it = ch.elements();
            while (it.hasNext()) {
                ExtractedNode c = toNode(it.next());
                if (c != null && c.getTitle() != null && !c.getTitle().isBlank()) {
                    children.add(c);
                }
            }
        }
        node.setChildren(children);
        return node;
    }

    private String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * 简单的文本生成调用 (不解析 JSON),返回原始文本结果。
     * 优先 SenseNova, 其次 MiniMax。
     */
    public String extractTextOnly(String systemPrompt, String userPrompt) {
        AiProperties.SenseNova snova = props.getSensenova();
        if (snova.isEnabled() && snova.getApiKey() != null && !snova.getApiKey().isBlank()) {
            return senseNovaClient.chatComplete(systemPrompt, userPrompt);
        }
        AiProperties.MiniMax mmax = props.getMinimax();
        if (mmax.isEnabled() && mmax.getApiKey() != null && !mmax.getApiKey().isBlank()) {
            return miniMaxClient.chatComplete(systemPrompt, userPrompt);
        }
        throw new IllegalStateException("SenseNova 与 MiniMax 均未启用,无法生成文本");
    }
}
