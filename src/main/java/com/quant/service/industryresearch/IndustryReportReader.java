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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 投研链路第二阶段：读研报 / 提炼核心结论
 *
 * 实现方式：
 *   - 默认走 MiniMaxClient.chatComplete() 直接调 MiniMax API
 *   - yml cli-mode.enabled=true 时改为走子进程调 minimax CLI
 *     (python3 llm_call.py --model ... --prompt ...)，
 *     复用 ~/.mavis/config.yaml 里配置的 provider
 *
 * Prompt 设计：
 *   要求 LLM 一次性吐完整结构化 JSON（11 Tab 全字段），下游 assembler 拿到后
 *   做"渲染 + 实时数据 merge + 缺失字段降级为 N/A"处理，而不是写死模板。
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
     * 读取研报并提炼。
     *
     * @param keyword     行业关键词（如 "液冷"、"AI 算力"）
     * @param maxCount    假定阅读的研报数（仅作为 LLM 提示用）
     * @param pdfContext  真实爬到的研报 PDF 文本（可为空）
     * @return 提炼结果：完整 11 Tab 结构化 JSON
     */
    public Map<String, Object> readAndDigest(String keyword, int maxCount, String pdfContext) {
        // 检查是否启用 LLM
        boolean llmEnabled = props.getKimiCli().isEnabled()
                || props.getCliMode().isEnabled();
        if (!llmEnabled) {
            log.info("[IndustryReportReader] LLM 模式未启用，回退到 mock 摘要 (keyword={})", keyword);
            return mockDigest(keyword, maxCount);
        }

        // CLI 模式需要 minimax CLI / llm_call.py 可用
        if (props.getCliMode().isEnabled()) {
            return readViaCli(keyword, maxCount, pdfContext);
        }

        // 直连模式需要 MiniMax API Key
        if (aiProps == null || aiProps.getMinimax() == null
                || !aiProps.getMinimax().isEnabled()
                || aiProps.getMinimax().getApiKey() == null
                || aiProps.getMinimax().getApiKey().isBlank()) {
            log.warn("[IndustryReportReader] MiniMax 未启用或未配置 Key，回退到 mock");
            return mockDigest(keyword, maxCount);
        }

        return readViaHttp(keyword, maxCount, pdfContext);
    }

    /* ====================== 路径 A：MiniMaxClient 直连 ====================== */

    private Map<String, Object> readViaHttp(String keyword, int maxCount, String pdfContext) {
        try {
            String systemPrompt = "你是一名资深产业研究员，擅长从卖方研报和一手资料中提炼结构化结论。"
                    + "请严格按用户指令只输出 JSON，不要任何 Markdown 包裹、不要解释性文字。";
            String userPrompt = buildUserPrompt(keyword, maxCount, pdfContext);

            log.info("[IndustryReportReader] 调用 MiniMax (HTTP) 摘要 (keyword={}, maxCount={}, prompt 长度={})",
                    keyword, maxCount, userPrompt.length());
            String raw = miniMaxClient.chatComplete(systemPrompt, userPrompt);
            log.debug("[IndustryReportReader] MiniMax 原始返回: {}", raw);

            return parseAndFallback(keyword, maxCount, raw, "HTTP");
        } catch (Exception e) {
            log.warn("[IndustryReportReader] LLM (HTTP) 摘要失败，回退到 mock: {}", e.getMessage());
            return mockDigest(keyword, maxCount);
        }
    }

    /* ====================== 路径 B：minimax CLI 子进程 ====================== */

    private Map<String, Object> readViaCli(String keyword, int maxCount, String pdfContext) {
        IndustryResearchProperties.CliMode cliCfg = props.getCliMode();
        String scriptPath = resolvePlaceholder(cliCfg.getScriptPath());
        String python = cliCfg.getPythonCommand();
        String model = cliCfg.getModel();

        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            log.warn("[IndustryReportReader] llm_call.py 不存在 path={}，回退到 mock", scriptPath);
            return mockDigest(keyword, maxCount);
        }

        try {
            String systemPrompt = "你是一名资深产业研究员，擅长从卖方研报和一手资料中提炼结构化结论。"
                    + "请严格按用户指令只输出 JSON，不要任何 Markdown 包裹、不要解释性文字。";
            String userPrompt = buildUserPrompt(keyword, maxCount, pdfContext);

            List<String> cmd = new ArrayList<>(List.of(
                    python,
                    scriptPath,
                    "--model", model,
                    "--system", systemPrompt,
                    "--prompt", userPrompt,
                    "--max-tokens", String.valueOf(cliCfg.getMaxTokens() != null ? cliCfg.getMaxTokens() : 8192),
                    "--temperature", "0.2"
            ));

            log.info("[IndustryReportReader] 调用 minimax CLI (script={}, model={}, prompt 长度={})",
                    scriptPath, model, userPrompt.length());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }

            boolean finished = proc.waitFor(cliCfg.getTimeoutSeconds() != null
                    ? cliCfg.getTimeoutSeconds() : 600, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("[IndustryReportReader] minimax CLI 调用超时，回退到 mock");
                return mockDigest(keyword, maxCount);
            }
            int exitCode = proc.exitValue();
            String raw = out.toString();
            if (exitCode != 0) {
                log.warn("[IndustryReportReader] minimax CLI 退出码={} 输出={}", exitCode,
                        raw.length() > 500 ? raw.substring(0, 500) + "..." : raw);
                return mockDigest(keyword, maxCount);
            }
            log.debug("[IndustryReportReader] minimax CLI 原始返回: {}", raw);
            return parseAndFallback(keyword, maxCount, raw, "CLI");
        } catch (Exception e) {
            log.warn("[IndustryReportReader] LLM (CLI) 摘要失败，回退到 mock: {}", e.getMessage());
            return mockDigest(keyword, maxCount);
        }
    }

    private String resolvePlaceholder(String s) {
        if (s == null) return null;
        return s.replace("${user.home}", System.getProperty("user.home"));
    }

    /* ====================== 解析 + 兜底字段 ====================== */

    private Map<String, Object> parseAndFallback(String keyword, int maxCount, String raw, String via) {
        try {
            String json = stripCodeFence(raw);
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject()) {
                log.warn("[IndustryReportReader] LLM 返回不是 JSON 对象 (via={})，回退 mock", via);
                return mockDigest(keyword, maxCount);
            }
            Map<String, Object> result = MAPPER.convertValue(node, Map.class);

            // 兜底字段
            result.putIfAbsent("totalRead", maxCount);
            result.putIfAbsent("keyword", keyword);
            result.putIfAbsent("isMock", false);
            result.putIfAbsent("via", via);

            // 统计 keyPoints 数
            Object kp = result.get("keyPoints");
            int kpSize = (kp instanceof List) ? ((List<?>) kp).size() : 0;
            log.info("[IndustryReportReader] LLM (via={}) 摘要成功: totalRead={}, keyPoints={}",
                    via, result.get("totalRead"), kpSize);
            return result;
        } catch (Exception e) {
            log.warn("[IndustryReportReader] LLM (via={}) JSON 解析失败: {}，回退 mock", via, e.getMessage());
            return mockDigest(keyword, maxCount);
        }
    }

    /* ====================== Prompt 构造 ====================== */

    private String buildUserPrompt(String keyword, int maxCount, String pdfContext) {
        String template = loadPromptTemplate();
        String pdfSection = (pdfContext == null || pdfContext.isBlank())
                ? "（本次未抓取到研报 PDF，模型请基于行业知识生成）"
                : truncate(pdfContext, props.getPdfFetch().getMaxReportChars());
        return template
                .replace("{{KEYWORD}}", keyword)
                .replace("{{MAX_COUNT}}", String.valueOf(maxCount))
                .replace("{{PDF_TEXTS}}", pdfSection);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource res = new ClassPathResource(props.getKimiCli().getPromptTemplate());
            if (res.exists()) {
                return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignore) {
        }
        return defaultPromptTemplate();
    }

    /**
     * 默认 prompt 模板：要求 LLM 吐完整 11 Tab 结构化 JSON。
     */
    private String defaultPromptTemplate() {
        return "假设你刚通读了约 {{MAX_COUNT}} 篇关于「{{KEYWORD}}」产业链的卖方研报和一手资料，"
                + "以下是部分研报 PDF 真实文本（已截断）：\n"
                + "{{PDF_TEXTS}}\n\n"
                + "请以产业研究员视角，**只输出 JSON，不要解释、不要 Markdown 包裹**，"
                + "必须包含以下完整结构（字段可以缺失，但请尽量补齐；实在不知道的字段写空数组 [] 或空字符串）：\n"
                + "{\n"
                + "  \"totalRead\": {{MAX_COUNT}},\n"
                + "  \"keyword\": \"{{KEYWORD}}\",\n"
                + "  \"summaries\": [\n"
                + "    {\"title\": \"研报标题\", \"broker\": \"券商名\", \"rating\": \"买入/增持/中性\",\n"
                + "     \"keyPoints\": [\"结论1\", \"结论2\", \"结论3\"]}\n"
                + "  ],\n"
                + "  \"keyPoints\": [\n"
                + "    \"整个产业链的核心结论 1\",\n"
                + "    \"核心结论 2\",\n"
                + "    \"核心结论 3\",\n"
                + "    \"核心结论 4\",\n"
                + "    \"核心结论 5\"\n"
                + "  ],\n"
                + "  \"sourceDistribution\": {\"中金\": 100, \"中信\": 120},\n"
                + "  \"chain\": {\n"
                + "    \"upstream\":   [\"上游环节 1\", \"上游环节 2\", \"上游环节 3\"],\n"
                + "    \"midstream\":  [\"中游环节 1\", \"中游环节 2\", \"中游环节 3\"],\n"
                + "    \"downstream\": [\"下游环节 1\", \"下游环节 2\", \"下游环节 3\"],\n"
                + "    \"upstreamPct\": 35, \"midstreamPct\": 40, \"downstreamPct\": 25\n"
                + "  },\n"
                + "  \"valuation\": [\n"
                + "    {\"name\": \"龙头 1\", \"code\": \"6XXXXX\", \"peTTM\": 32, \"pe2025E\": 24, \"pb\": 5.2, \"peg\": 0.65, \"marketCapYi\": 580},\n"
                + "    {\"name\": \"龙头 2\", \"code\": \"3XXXXX\", \"peTTM\": 28, \"pe2025E\": 20, \"pb\": 4.8, \"peg\": 0.58, \"marketCapYi\": 320},\n"
                + "    {\"name\": \"龙头 3\", \"code\": \"6XXXXX\", \"peTTM\": 78, \"pe2025E\": 48, \"pb\": 12.6, \"peg\": 0.88, \"marketCapYi\": 1680}\n"
                + "  ],\n"
                + "  \"leaders\": [\n"
                + "    {\"name\": \"龙头 1\", \"code\": \"6XXXXX.SZ\", \"pe\": \"32x\", \"marketCap\": \"580\",\n"
                + "     \"logic\": \"全产业链布局，业绩兑现确定性强\", \"score\": 82, \"irreplaceablePct\": 90},\n"
                + "    {\"name\": \"龙头 2\", \"code\": \"3XXXXX.SZ\", \"pe\": \"28x\", \"marketCap\": \"320\",\n"
                + "     \"logic\": \"细分赛道绝对龙头\", \"score\": 78, \"irreplaceablePct\": 85},\n"
                + "    {\"name\": \"龙头 3\", \"code\": \"6XXXXX.SH\", \"pe\": \"78x\", \"marketCap\": \"1680\",\n"
                + "     \"logic\": \"国产替代核心标的\", \"score\": 70, \"irreplaceablePct\": 75}\n"
                + "  ],\n"
                + "  \"financials\": {\n"
                + "    \"avgPE\": \"38x\", \"avgROE\": \"16.2%\", \"avgGrossMargin\": \"32%\", \"revenueYoY\": \"+45%\",\n"
                + "    \"rows\": [\n"
                + "      {\"name\": \"龙头 1\", \"revenueYi\": 195, \"revenueYoY\": \"+120%\", \"netMargin\": \"23%\", \"roe\": \"28%\"},\n"
                + "      {\"name\": \"龙头 2\", \"revenueYi\": 88,  \"revenueYoY\": \"+85%\",  \"netMargin\": \"18%\", \"roe\": \"22%\"},\n"
                + "      {\"name\": \"龙头 3\", \"revenueYi\": 62,  \"revenueYoY\": \"+62%\",  \"netMargin\": \"12%\", \"roe\": \"15%\"}\n"
                + "    ]\n"
                + "  },\n"
                + "  \"fundFlow\": {\n"
                + "    \"mainInflow5dYi\": 38, \"northInflow5dYi\": 12, \"marginBalanceYi\": 280, \"turnoverPct\": 3.2\n"
                + "  },\n"
                + "  \"policy\": [\n"
                + "    {\"level\": \"info\", \"tag\": \"政策\", \"text\": \"国家层面「{{KEYWORD}}」被列入战略性新兴产业\"},\n"
                + "    {\"level\": \"ok\",   \"tag\": \"支持\", \"text\": \"专项基金 + 税收优惠 + 国产化采购倾斜\"},\n"
                + "    {\"level\": \"warn\", \"tag\": \"风险\", \"text\": \"海外出口管制升级，关注关键设备 / 材料断供\"}\n"
                + "  ],\n"
                + "  \"tech\": {\n"
                + "    \"current\":  {\"name\": \"当前\",   \"perf\": \"1x\",   \"power\": \"1x\",   \"value\": \"1x\"},\n"
                + "    \"next\":     {\"name\": \"下一代\", \"perf\": \"2x\",   \"power\": \"1.3x\", \"value\": \"1.6x\"},\n"
                + "    \"nextTwo\":  {\"name\": \"下两代\", \"perf\": \"4x\",   \"power\": \"1.6x\", \"value\": \"2.4x\"}\n"
                + "  },\n"
                + "  \"globalPlayers\": [\n"
                + "    {\"name\": \"海外龙头 1\", \"country\": \"美国\",   \"share\": 40, \"advantage\": \"技术先发\"},\n"
                + "    {\"name\": \"海外龙头 2\", \"country\": \"韩国\",   \"share\": 25, \"advantage\": \"规模效应\"},\n"
                + "    {\"name\": \"海外龙头 3\", \"country\": \"日本\",   \"share\": 15, \"advantage\": \"工艺壁垒\"},\n"
                + "    {\"name\": \"中国龙头 A\", \"country\": \"中国\",   \"share\": 10, \"advantage\": \"国产替代 + 服务响应\"},\n"
                + "    {\"name\": \"中国龙头 B\", \"country\": \"中国\",   \"share\": 7,  \"advantage\": \"成本优势\"},\n"
                + "    {\"name\": \"其他\",      \"country\": \"其他\",   \"share\": 3,  \"advantage\": \"—\"}\n"
                + "  ],\n"
                + "  \"risks\": [\n"
                + "    {\"level\": \"warn\", \"tag\": \"周期\",   \"text\": \"Capex 周期已上行 18 个月，存在见顶风险\"},\n"
                + "    {\"level\": \"warn\", \"tag\": \"估值\",   \"text\": \"龙头估值已较 2023 年低点翻倍，PE 分位 > 70%\"},\n"
                + "    {\"level\": \"warn\", \"tag\": \"客户\",   \"text\": \"前五大客户占比 80%+，单一客户波动影响显著\"},\n"
                + "    {\"level\": \"info\", \"tag\": \"技术\",   \"text\": \"下一代技术路线存在不确定性，可能颠覆现有格局\"},\n"
                + "    {\"level\": \"ok\",   \"tag\": \"对冲\",   \"text\": \"国产替代 + 出海双逻辑可对冲北美周期波动\"}\n"
                + "  ]\n"
                + "}\n";
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
