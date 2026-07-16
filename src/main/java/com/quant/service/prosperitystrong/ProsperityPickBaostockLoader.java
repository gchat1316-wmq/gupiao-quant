package com.quant.service.prosperitystrong;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.config.StockAnalysisProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Wraps the baostock pack subprocess invoked by the stock-analysis Python bridge. Returns the raw
 * JSON map for downstream scoring / report rendering. Disabled when {@code
 * stock-analysis.enabled=false}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProsperityPickBaostockLoader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final StockAnalysisProperties stockAnalysisProperties;

  @SuppressWarnings("unchecked")
  public Map<String, Object> fetchBaostockPack(String stockCode) {
    if (!stockAnalysisProperties.isEnabled()) {
      log.info("stock-analysis 模块未启用，跳过 baostock");
      return null;
    }
    String code = normalizeCode(stockCode);
    try {
      List<String> cmd =
          new ArrayList<>(
              List.of(
                  stockAnalysisProperties.getPythonCommand(),
                  stockAnalysisProperties.getPythonScript(),
                  "pack",
                  code,
                  String.valueOf(60),
                  String.valueOf(2),
                  "--lite"));
      log.info("调 baostock: {}", String.join(" ", cmd));
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      Process process = pb.start();
      StringBuilder stdout = new StringBuilder();
      try (var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) stdout.append(line);
      }
      boolean done = process.waitFor(stockAnalysisProperties.getTimeoutSeconds(), TimeUnit.SECONDS);
      if (!done) {
        process.destroyForcibly();
        throw new RuntimeException(
            "baostock 调用超限 (" + stockAnalysisProperties.getTimeoutSeconds() + "s)");
      }
      if (process.exitValue() != 0) {
        throw new RuntimeException("baostock 退出码 " + process.exitValue());
      }
      String content = stdout.toString();
      int idx = content.indexOf('{');
      if (idx < 0) throw new RuntimeException("baostock 输出无 JSON");
      return MAPPER.readValue(content.substring(idx), Map.class);
    } catch (Exception e) {
      throw new RuntimeException("baostock 调用失败: " + e.getMessage(), e);
    }
  }

  String normalizeCode(String code) {
    if (code == null) return "";
    code = code.trim().toLowerCase();
    if (code.contains(".")) return code;
    if (code.matches("\\d{6}")) {
      if (code.startsWith("60") || code.startsWith("68") || code.startsWith("90"))
        return "sh." + code;
      if (code.startsWith("00") || code.startsWith("30") || code.startsWith("20"))
        return "sz." + code;
      if (code.startsWith("43")
          || code.startsWith("83")
          || code.startsWith("87")
          || code.startsWith("88")) return "bj." + code;
    }
    return code;
  }
}
