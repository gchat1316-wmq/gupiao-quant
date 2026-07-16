package com.quant.service.prosperitystrong;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.config.ProsperityStrongProperties;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WindAifinMarketClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ProsperityStrongProperties props;

  public boolean isInstalled() {
    Path dir = Path.of(props.getWind().getSkillDir());
    return Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("scripts/cli.mjs"));
  }

  public boolean hasApiKey() {
    return apiKey().isPresent();
  }

  public WindCheck verify() {
    if (!isInstalled()) {
      return new WindCheck(false, false, "Wind skill 未安装或 skillDir 不存在");
    }
    Optional<String> key = apiKey();
    if (key.isEmpty()) {
      return new WindCheck(true, false, "未找到 WIND_API_KEY,请设置环境变量或全局配置文件");
    }
    try {
      JsonNode node =
          call(
              "stock_data",
              "get_stock_price_indicators",
              Map.of(
                  "windcode", "600519.SH",
                  "indexes", "中文简称,最新成交价,涨跌幅"));
      String text = node.toString();
      boolean ok = text.contains("贵州茅台") || text.contains("600519");
      return new WindCheck(true, ok, ok ? "Wind 行情链路已验证" : "Wind CLI 可调用,但返回内容未命中校验样本");
    } catch (Exception e) {
      return new WindCheck(true, false, "Wind 验证失败: " + e.getMessage());
    }
  }

  public JsonNode call(String serverType, String toolName, Map<String, Object> args)
      throws IOException, InterruptedException {
    Path skillDir = Path.of(props.getWind().getSkillDir());
    String payload = MAPPER.writeValueAsString(args);
    ProcessBuilder pb =
        new ProcessBuilder("node", "scripts/cli.mjs", "call", serverType, toolName, payload);
    pb.directory(skillDir.toFile());
    Map<String, String> env = pb.environment();
    apiKey().ifPresent(k -> env.put("WIND_API_KEY", k));
    Process p = pb.start();
    boolean done = p.waitFor(props.getWind().getTimeoutSeconds(), TimeUnit.SECONDS);
    if (!done) {
      p.destroyForcibly();
      throw new IOException("Wind CLI 超时(" + props.getWind().getTimeoutSeconds() + "s)");
    }
    String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    if (p.exitValue() != 0) {
      throw new IOException(stderr.isBlank() ? stdout : stderr);
    }
    return parseJsonFromOutput(stdout);
  }

  public Map<String, Object> searchStocks(String question, int limit)
      throws IOException, InterruptedException {
    JsonNode root = call("stock_data", "search_stocks", Map.of("question", question));
    JsonNode textNode =
        root.path("content").isArray() && !root.path("content").isEmpty()
            ? root.path("content").get(0).path("text")
            : null;
    if (textNode == null || textNode.isMissingNode()) {
      return Map.of("question", question, "rows", java.util.List.of(), "message", "Wind 未返回表格文本");
    }
    JsonNode inner = MAPPER.readTree(textNode.asText());
    JsonNode table =
        inner.path("data").path("data").isArray() && !inner.path("data").path("data").isEmpty()
            ? inner.path("data").path("data").get(0)
            : null;
    if (table == null || table.isMissingNode()) {
      return Map.of(
          "question", question, "rows", java.util.List.of(), "message", "Wind 返回结构中未找到表格");
    }

    ArrayList<String> columns = new ArrayList<>();
    for (JsonNode col : table.path("columns")) {
      columns.add(col.path("name").asText());
    }
    ArrayList<Map<String, Object>> rows = new ArrayList<>();
    int max = Math.max(1, Math.min(limit, 200));
    for (JsonNode row : table.path("rows")) {
      if (rows.size() >= max) break;
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      for (int i = 0; i < columns.size() && i < row.size(); i++) {
        JsonNode value = row.get(i);
        Object v = value.isNumber() ? value.numberValue() : value.asText();
        item.put(columns.get(i), v);
      }
      rows.add(item);
    }
    return Map.of(
        "question",
        question,
        "resolvedQuestion",
        table.path("resolved_question").asText(question),
        "total",
        table.path("excelTotalCount").asInt(rows.size()),
        "rows",
        rows);
  }

  private Optional<String> apiKey() {
    String env = System.getenv("WIND_API_KEY");
    if (env != null && !env.isBlank()) {
      return Optional.of(env.trim());
    }
    Path config = Path.of(props.getWind().getConfigPath());
    if (!Files.isRegularFile(config)) {
      return Optional.empty();
    }
    try (BufferedReader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.startsWith("WIND_API_KEY=")) {
          String value = trimmed.substring("WIND_API_KEY=".length()).trim();
          if (!value.isBlank()) return Optional.of(value);
        }
      }
    } catch (IOException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private JsonNode parseJsonFromOutput(String stdout) throws IOException {
    String text = stdout == null ? "" : stdout.trim();
    if (text.isBlank()) {
      throw new IOException("Wind CLI 返回为空");
    }
    try {
      return MAPPER.readTree(text);
    } catch (Exception ignored) {
      int start = firstJsonStart(text);
      int end = text.lastIndexOf('}');
      if (start >= 0 && end > start) {
        return MAPPER.readTree(text.substring(start, end + 1));
      }
      throw new IOException("Wind CLI 返回非 JSON: " + abbreviate(text));
    }
  }

  private int firstJsonStart(String text) {
    int obj = text.indexOf('{');
    int arr = text.indexOf('[');
    if (obj < 0) return arr;
    if (arr < 0) return obj;
    return Math.min(obj, arr);
  }

  private String abbreviate(String text) {
    return text.length() <= 180 ? text : text.substring(0, 180) + "...";
  }

  public record WindCheck(boolean installed, boolean verified, String message) {}
}
