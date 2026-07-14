package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.AiProperties;
import com.quant.entity.InvestMarketRecap;
import com.quant.repository.InvestMarketRecapRepository;
import com.quant.service.ai.MiniMaxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 每日市场复盘：
 *  - 数据采集：调用 Python 脚本从东方财富/腾讯财经/akshare 获取实时行情
 *  - AI 生成：调 MiniMax 生成九模块复盘
 *  - 持久化：写入 invest_market_recap 表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyRecapService {

    private static final String AI_SYSTEM_PROMPT =
            "你是 A 股资深每日复盘分析师，输出纯 JSON（含所有字段），不使用 markdown。";

    private final InvestMarketRecapRepository repository;
    private final MiniMaxClient miniMaxClient;
    private final AiProperties aiProps;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 采集团队 */
    private static final String[] PYTHON_CMD = {"python3",
        "scripts/daily_recap/fetch_data.py"};

    /** 指数中文名映射（用于 AI prompt） */
    private static final Map<String, String> INDEX_NAMES = Map.of(
            "上证指数", "sh000001",
            "深证成指", "sz399001",
            "沪深300", "sh000300",
            "创业板指", "sz399006",
            "上证50", "sh000016",
            "科创50", "sh000688"
    );

    // ── 手动触发 ──────────────────────────────────────────────────────────────

    /** 手动触发 A 股复盘（供 Controller 调用） */
    @Transactional
    public Long triggerAShare() {
        return runRecap("A股");
    }

    /** 手动触发美股复盘 */
    @Transactional
    public Long triggerUsMarket() {
        return runRecap("美股");
    }

    // ── Cron 定时触发 ────────────────────────────────────────────────────────

    /** A 股：每个交易日下午 16:00 */
    @Scheduled(cron = "${daily-recap.a-cron:0 0 16 * * MON-FRI}")
    @EventListener(ApplicationReadyEvent.class)
    public void scheduledAShare() {
        safeRun("A股");
    }

    /** 美股：每个交易日上午 07:30（北京） */
    @Scheduled(cron = "${daily-recap.us-cron:0 30 7 * * MON-FRI}")
    public void scheduledUsMarket() {
        safeRun("美股");
    }

    // ── 核心逻辑 ──────────────────────────────────────────────────────────────

    private void safeRun(String market) {
        try {
            Long id = runRecap(market);
            log.info("每日复盘完成 market={} id={}", market, id);
        } catch (Exception e) {
            log.error("每日复盘失败 market={} err={}", market, e.getMessage(), e);
            try {
                notificationService.sendServerChan(market + " 复盘失败",
                        "错误: " + e.getMessage());
            } catch (Exception ignored) {}
        }
    }

    @Transactional
    public Long runRecap(String market) {
        long t0 = System.currentTimeMillis();
        log.info("开始复盘 market={}", market);

        // 1. 采集数据
        Map<String, Object> data;
        try {
            data = collectData(market);
        } catch (Exception e) {
            throw new RuntimeException("数据采集失败: " + e.getMessage(), e);
        }

        // 2. AI 生成复盘内容
        String recapMarkdown;
        try {
            recapMarkdown = generateRecap(market, data);
        } catch (Exception e) {
            log.warn("AI 生成失败，降级保存骨架数据 market={} err={}", market, e.getMessage());
            recapMarkdown = buildFallbackMarkdown(market, data);
        }

        // 3. 解析 AI 返回写库
        InvestMarketRecap recap = parseAndSave(market, recapMarkdown, data);

        log.info("复盘完成 market={} id={} cost={}ms", market, recap.getId(), System.currentTimeMillis() - t0);
        return recap.getId();
    }

    // ── 数据采集 ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> collectData(String market) throws Exception {
        String[] cmd = market.equals("A股")
                ? new String[]{"python3", "scripts/daily_recap/fetch_data.py", "--market", "A股"}
                : new String[]{"python3", "scripts/daily_recap/fetch_data.py", "--market", "美股"};

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = r.lines().collect(Collectors.joining());
        }

        boolean done = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            throw new RuntimeException("Python 采集超时 (60s)");
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("Python 采集失败 exit=" + process.exitValue() + ": " + output);
        }

        return objectMapper.readValue(output, LinkedHashMap.class);
    }

    // ── AI 生成 ───────────────────────────────────────────────────────────────

    private String generateRecap(String market, Map<String, Object> data) throws Exception {
        String prompt = buildPrompt(market, data);
        String result = miniMaxClient.chatComplete(AI_SYSTEM_PROMPT, prompt);
        if (result == null || result.isBlank()) {
            throw new RuntimeException("AI 返回为空");
        }
        // 去掉可能的 markdown 代码块包裹
        result = result.trim();
        if (result.startsWith("```")) {
            int idx = result.indexOf("\n");
            if (idx > 0) result = result.substring(idx + 1);
            result = result.replaceAll("```$", "").trim();
        }
        return result;
    }

    private String buildPrompt(String market, Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为 ").append(market).append(" 生成今日（")
                .append(LocalDate.now().format(DateTimeFormatter.ISO_DATE))
                .append("）每日复盘。\n\n数据如下：\n");
        sb.append("```json\n");
        sb.append(toPrettyJson(data));
        sb.append("\n```\n\n");
        sb.append("请返回以下格式的纯 JSON（不包含 markdown 代码块包裹）：\n\n");
        sb.append("{\n");
        sb.append("  \"title\": \"复盘标题，如：【A股复盘】2026-07-14 强势普涨\",\n");
        sb.append("  \"content\": \"Markdown 格式的完整复盘正文（可包含表格、加粗等Markdown语法）\",\n");
        sb.append("  \"indexes_summary\": \"指数涨跌摘要，如：上证 +1.36% | 深证 +2.77% | 创业板 +3.43%\",\n");
        sb.append("  \"advance_decline\": \"涨跌家数，如：涨2100/跌900\",\n");
        sb.append("  \"limit_up\": 涨停数（整数），\n");
        sb.append("  \"limit_down\": 跌停数（整数），\n");
        sb.append("  \"sentiment\": \"市场情绪定性，如：强势普涨｜创业板领涨\",\n");
        sb.append("  \"sectors\": \"主线板块（多选，逗号分隔）\",\n");
        sb.append("  \"risks\": \"风险提示（最多4条，分号分隔）\",\n");
        sb.append("  \"key_data\": \"关键数字（如：创业板 +3.43% 成交 X 亿），分号分隔\",\n");
        sb.append("  \"catalysts\": \"催化事件（逗号分隔），如无填「暂无」\",\n");
        sb.append("  \"next_day_strategy\": \"次日策略建议，50字以内\"\n");
        sb.append("}\n\n");
        sb.append("注意：\n");
        sb.append("- content 为 Markdown 格式，包含九大模块：指数快照、市场温度、板块结构、");
        sb.append("涨停分析、跌停分析、科技动态、催化事件、风险提示、次日策略\n");
        sb.append("- 各整数字段（limit_up/down）如实填写，没有则填 0\n");
        sb.append("- 不要输出除 JSON 以外任何内容");
        return sb.toString();
    }

    private String toPrettyJson(Object data) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (Exception e) {
            return data.toString();
        }
    }

    private String buildFallbackMarkdown(String market, Map<String, Object> data) {
        @SuppressWarnings("unchecked")
        var indexes = (java.util.List<Map<String, Object>>) data.getOrDefault("indexes", java.util.Collections.emptyList());
        String idxSummary = indexes.stream()
                .map(m -> {
                    String n = String.valueOf(m.getOrDefault("name", ""));
                    Object pct = m.getOrDefault("change_pct", 0);
                    String sign = ((Number) pct).doubleValue() > 0 ? "+" : "";
                    return n + sign + pct + "%";
                })
                .collect(Collectors.joining(" | "));
        String date = String.valueOf(data.getOrDefault("date", LocalDate.now().toString()));
        return String.format(
                "# 【%s 复盘】%s\n\n## 指数\n%s\n\n*数据来源：腾讯财经实时行情（降级骨架版，AI 生成失败）*",
                market, date, idxSummary);
    }

    // ── 解析并保存 ────────────────────────────────────────────────────────────

    @Transactional
    public InvestMarketRecap parseAndSave(String market, String aiJson, Map<String, Object> rawData) {
        InvestMarketRecap recap = new InvestMarketRecap();
        recap.setMarket(market);
        recap.setRecapDate(LocalDate.now());
        recap.setRecapType("evening");
        recap.setTradeDate(LocalDate.now());
        recap.setContent(aiJson); // 存原始 AI JSON，前端/Service 层自行渲染

        try {
            JsonNode node = objectMapper.readTree(aiJson);
            recap.setTitle(nullSafe(node.path("title").asText("")));
            recap.setIndexesSummary(nullSafe(node.path("indexes_summary").asText("")));
            recap.setAdvanceDecline(nullSafe(node.path("advance_decline").asText("")));
            recap.setLimitUp(node.path("limit_up").canConvertToInt() ? node.path("limit_up").asInt() : 0);
            recap.setLimitDown(node.path("limit_down").canConvertToInt() ? node.path("limit_down").asInt() : 0);
            recap.setSentiment(nullSafe(node.path("sentiment").asText("")));
            recap.setSectors(nullSafe(node.path("sectors").asText("")));
            recap.setRisks(nullSafe(node.path("risks").asText("")));
            recap.setKeyData(nullSafe(node.path("key_data").asText("")));
            recap.setCatalysts(nullSafe(node.path("catalysts").asText("")));
            recap.setNextDayStrategy(nullSafe(node.path("next_day_strategy").asText("")));
        } catch (Exception e) {
            log.warn("AI 返回 JSON 解析失败，使用骨架 market={} err={}", market, e.getMessage());
            recap.setTitle("【" + market + " 复盘】" + LocalDate.now());
        }

        return repository.save(recap);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
