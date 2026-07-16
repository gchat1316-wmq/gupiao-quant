package com.quant.service.tdx;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.config.ProsperityStrongProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 通达信 MCP 客户端（API Key 直调模式）。
 *
 * <p>与 WorkBuddy 客户端的 OAuth 流程不同——这里使用长期 API Key (TDX-c62ebd01...)， 通过 mcp.tdx.com.cn:3001/mcp 这个端点
 * + tdx-api-key header 直接调。
 *
 * <p>工具只有 1 个: tdx_wenda_quotes（自然语言问答, 返回结构化财务/行情/行业数据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TdxMcpClient {

  private final ProsperityStrongProperties props;
  private final TdxOAuthClient oauthClient; // 复用 getStatus 逻辑
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

  /** 缓存 session id, 避免每个请求都 initialize */
  private final ConcurrentHashMap<String, String> sessionIdCache = new ConcurrentHashMap<>();

  /** API Key 是否已配置 */
  public boolean isAuthorized() {
    String apiKey = props.getTdx().getApiKey();
    return apiKey != null && !apiKey.isBlank();
  }

  /**
   * 自然语言查询: 返回表格数据 (headers + data[][])
   *
   * @param question 中文自然语言
   */
  public Optional<JsonNode> ask(String question) {
    if (!isAuthorized()) {
      log.debug("TDX API Key 未配置, 跳过 ask({})", question);
      return Optional.empty();
    }
    try {
      // 1) ensure session
      String sessionId = ensureSession();
      if (sessionId == null) return Optional.empty();
      // 2) tools/call tdx_wenda_quotes
      Map<String, Object> params =
          Map.of("name", "tdx_wenda_quotes", "arguments", Map.of("question", question));
      Map<String, Object> rpc = new LinkedHashMap<>();
      rpc.put("jsonrpc", "2.0");
      rpc.put("id", 1);
      rpc.put("method", "tools/call");
      rpc.put("params", params);
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(props.getTdx().getMcpUrl()))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .header("tdx-api-key", props.getTdx().getApiKey())
              .header("mcp-session-id", sessionId)
              .timeout(Duration.ofSeconds(props.getTdx().getTimeoutSeconds()))
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(rpc)))
              .build();
      HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 401) {
        log.warn("TDX 401, API Key 可能无效");
        return Optional.empty();
      }
      if (resp.statusCode() / 100 != 2) {
        log.warn("TDX MCP 错误 {}: {}", resp.statusCode(), resp.body());
        return Optional.empty();
      }
      // SSE 格式: "event: message\ndata: {...}\n\n"
      String body = resp.body();
      int idx = body.indexOf("data: ");
      if (idx < 0) return Optional.empty();
      String jsonLine = body.substring(idx + 6).trim();
      int newlineIdx = jsonLine.indexOf('\n');
      if (newlineIdx > 0) jsonLine = jsonLine.substring(0, newlineIdx).trim();
      JsonNode root = objectMapper.readTree(jsonLine);
      JsonNode result = root.get("result");
      if (result == null) return Optional.empty();
      JsonNode content = result.get("content");
      if (content != null && content.isArray() && !content.isEmpty()) {
        JsonNode firstText = content.get(0).get("text");
        if (firstText != null) {
          try {
            return Optional.of(objectMapper.readTree(firstText.asText()));
          } catch (Exception parseEx) {
            return Optional.of(objectMapper.createObjectNode().set("text", firstText));
          }
        }
      }
      return Optional.of(result);
    } catch (Exception e) {
      log.warn("TDX ask(\"{}\") 失败: {}", question, e.getMessage());
      return Optional.empty();
    }
  }

  /** 把 TDX 返回的表格格式 (headers + data[][]) 转成可读文本 */
  public static String tableToText(JsonNode tdxResponse, int maxRows) {
    if (tdxResponse == null) return "";
    JsonNode headers = tdxResponse.get("headers");
    JsonNode data = tdxResponse.get("data");
    if (headers == null || data == null || !data.isArray() || data.isEmpty()) {
      return "（TDX 返回空, total=" + tdxResponse.path("meta").path("total").asInt() + "）";
    }
    StringBuilder sb = new StringBuilder();
    // 表头
    for (int i = 0; i < headers.size(); i++) {
      if (i > 0) sb.append(" | ");
      sb.append(headers.get(i).asText());
    }
    sb.append("\n");
    // 数据
    int rows = Math.min(maxRows, data.size());
    for (int r = 0; r < rows; r++) {
      JsonNode row = data.get(r);
      if (!row.isArray()) continue;
      for (int c = 0; c < row.size(); c++) {
        if (c > 0) sb.append(" | ");
        String v = row.get(c).asText("");
        if (v.length() > 60) v = v.substring(0, 60) + "...";
        sb.append(v);
      }
      sb.append("\n");
    }
    if (data.size() > maxRows) {
      sb.append("（还有 ").append(data.size() - maxRows).append(" 行省略）\n");
    }
    return sb.toString();
  }

  /** 复用 OAuthClient 的 status 接口, 保持前端 API 一致 */
  public TdxOAuthClient.Status getStatus() {
    TdxOAuthClient.Status base = oauthClient.getStatus();
    return new TdxOAuthClient.Status(
        isAuthorized(),
        base.pending(),
        base.clientId(),
        base.expiresAtEpochMs(),
        base.redirectUri());
  }

  private String ensureSession() {
    String cached = sessionIdCache.get("default");
    if (cached != null) return cached;
    try {
      Map<String, Object> rpc =
          Map.of(
              "jsonrpc",
              "2.0",
              "id",
              1,
              "method",
              "initialize",
              "params",
              Map.of(
                  "protocolVersion", "2024-11-05",
                  "capabilities", Map.of(),
                  "clientInfo", Map.of("name", "gupiao-quant", "version", "1.0")));
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(props.getTdx().getMcpUrl()))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .header("tdx-api-key", props.getTdx().getApiKey())
              .timeout(Duration.ofSeconds(15))
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(rpc)))
              .build();
      HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        log.warn("TDX initialize 失败: {} {}", resp.statusCode(), resp.body());
        return null;
      }
      String sessionId = resp.headers().firstValue("mcp-session-id").orElse(null);
      if (sessionId == null) {
        // 兜底: 在 body 里找
        int idx = resp.body().indexOf("mcp-session-id");
        if (idx > 0) {
          int end = resp.body().indexOf('\n', idx);
          String line = resp.body().substring(idx, end > 0 ? end : idx + 60);
          int colon = line.indexOf(':');
          if (colon > 0) sessionId = line.substring(colon + 1).trim();
        }
      }
      if (sessionId == null || sessionId.isBlank()) {
        log.warn("TDX initialize 响应里没找到 mcp-session-id");
        return null;
      }
      sessionIdCache.put("default", sessionId);
      log.info("TDX initialize 成功, sessionId={}", sessionId);
      return sessionId;
    } catch (Exception e) {
      log.warn("TDX initialize 异常: {}", e.getMessage());
      return null;
    }
  }
}
