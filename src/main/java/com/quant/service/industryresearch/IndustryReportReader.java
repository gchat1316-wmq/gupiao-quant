package com.quant.service.industryresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Kimi CLI 读研报：调用本地 kimi 命令批量阅读大量研报，节省 Claude/M2 token
 * 对应投研链路第二阶段：读研报
 *
 * 如果 Kimi CLI 未启用或不可用，回退到 mock 摘要（用于演示和开发）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryReportReader {

    private final ObjectMapper mapper = new ObjectMapper();
    private final IndustryResearchProperties props;

    /**
     * 读取研报并提炼
     *
     * @param keyword  行业关键词（如 "AI 算力"）
     * @param maxCount 最多读取多少篇
     * @return 提炼结果：{ "totalRead": N, "summaries": [...], "keyPoints": [...], "sourceDistribution": {...} }
     */
    public Map<String, Object> readAndDigest(String keyword, int maxCount) {
        if (!props.getKimiCli().isEnabled()) {
            log.info("[IndustryReportReader] Kimi CLI 未启用，回退到 mock 摘要 (keyword={})", keyword);
            return mockDigest(keyword, maxCount);
        }

        try {
            // 1. 准备 prompt 模板
            String promptTemplate = loadPromptTemplate();

            // 2. 把 prompt 写到临时文件（避免命令行长度限制）
            Path tmpPrompt = Files.createTempFile("kimi-prompt-", ".txt");
            String prompt = promptTemplate
                    .replace("{{KEYWORD}}", keyword)
                    .replace("{{MAX_COUNT}}", String.valueOf(maxCount));
            Files.writeString(tmpPrompt, prompt, StandardCharsets.UTF_8);

            // 3. 调用 kimi CLI: kimi --prompt-file <path> --output json
            List<String> cmd = new ArrayList<>();
            cmd.add(props.getKimiCli().getCommand());
            cmd.add("--prompt-file");
            cmd.add(tmpPrompt.toString());
            cmd.add("--output");
            cmd.add("json");

            log.info("[IndustryReportReader] 调用 Kimi CLI: {}", cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .directory(new File("."));
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(props.getKimiCli().getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[IndustryReportReader] Kimi CLI 超时 ({}s)，回退到 mock", props.getKimiCli().getTimeoutSeconds());
                return mockDigest(keyword, maxCount);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("[IndustryReportReader] Kimi CLI exit={}, 回退到 mock. Output: {}", exitCode, output);
                return mockDigest(keyword, maxCount);
            }

            // 4. 解析 JSON 输出
            Files.deleteIfExists(tmpPrompt);
            JsonNode node = mapper.readTree(output.toString());
            Map<String, Object> result = mapper.convertValue(node, Map.class);
            log.info("[IndustryReportReader] Kimi CLI 返回: {} 篇", result.get("totalRead"));
            return result;

        } catch (Exception e) {
            log.warn("[IndustryReportReader] 调用异常，回退到 mock: {}", e.getMessage());
            return mockDigest(keyword, maxCount);
        }
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource res = new ClassPathResource(props.getKimiCli().getPromptTemplate());
            if (!res.exists()) {
                log.warn("prompt 模板 {} 不存在，使用内置默认模板", props.getKimiCli().getPromptTemplate());
                return defaultPromptTemplate();
            }
            return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return defaultPromptTemplate();
        }
    }

    private String defaultPromptTemplate() {
        return "你是一个产业投研助手。请阅读关于「{{KEYWORD}}」的研报（最多 {{MAX_COUNT}} 篇），\n" +
                "输出 JSON 格式：\n" +
                "{\n" +
                "  \"totalRead\": 实际阅读篇数,\n" +
                "  \"summaries\": [{\"title\": \"...\", \"broker\": \"...\", \"keyPoints\": [\"...\"]}],\n" +
                "  \"keyPoints\": [\"整个行业的核心结论 1\", \"核心结论 2\", \"...\"]\n" +
                "}\n";
    }

    /**
     * Mock 摘要（演示用，避免外网/CLI 不可用时流水线断）
     */
    private Map<String, Object> mockDigest(String keyword, int maxCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        int totalRead = Math.min(maxCount, 1_171); // 演示：保持和视频里一致
        out.put("totalRead", totalRead);
        out.put("keyword", keyword);

        List<Map<String, Object>> summaries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("title", keyword + " 行业研究 · " + (i + 1));
            s.put("broker", List.of("中金", "中信", "华泰", "招商", "国君").get(i));
            s.put("rating", List.of("买入", "增持", "买入", "强烈推荐", "买入").get(i));
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