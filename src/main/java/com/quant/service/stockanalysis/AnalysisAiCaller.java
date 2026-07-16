package com.quant.service.stockanalysis;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 调用 + JSON 解析。 从 {@code StockAnalysisService.analyzeWithAi / parseAiJson / extractJson} 拆出。
 *
 * <p>默认 MiniMax → 失败回退 SenseNova；两者都失败抛 IllegalStateException。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisAiCaller {

  private final MiniMaxClient miniMaxClient;
  private final SenseNovaClient senseNovaClient;
  private final ObjectMapper objectMapper;

  /** 优先 MiniMax，失败回退 SenseNova，两边都失败抛 IllegalStateException。 */
  public Map<String, Object> analyze(String systemPrompt, String userPrompt) {
    Exception miniMaxError;
    try {
      return parseAiJson(miniMaxClient.chatComplete(systemPrompt, userPrompt));
    } catch (Exception e) {
      miniMaxError = e;
      log.warn("MiniMax 分析失败，尝试 SenseNova: {}", e.getMessage());
    }
    try {
      return parseAiJson(senseNovaClient.chatComplete(systemPrompt, userPrompt));
    } catch (Exception senseNovaError) {
      String message =
          "MiniMax: " + miniMaxError.getMessage() + "; SenseNova: " + senseNovaError.getMessage();
      throw new IllegalStateException("AI 调用失败: " + message, senseNovaError);
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> parseAiJson(String raw) {
    try {
      return objectMapper.readValue(extractJson(raw), Map.class);
    } catch (Exception e) {
      throw new IllegalStateException("AI 返回不是合法 JSON: " + e.getMessage(), e);
    }
  }

  /** 把 AI 返回里的 ```json ... ``` fence 去掉，并定位首尾大括号。 失败时返回原字符串（让 Jackson 自己报错）。 */
  public String extractJson(String raw) {
    if (raw == null) return "{}";
    String s = raw.trim();
    if (s.startsWith("```")) {
      int firstNewline = s.indexOf('\n');
      if (firstNewline > 0) s = s.substring(firstNewline + 1);
      int lastFence = s.lastIndexOf("```");
      if (lastFence > 0) s = s.substring(0, lastFence);
    }
    int start = s.indexOf('{');
    int end = s.lastIndexOf('}');
    return start >= 0 && end > start ? s.substring(start, end + 1) : s;
  }
}
