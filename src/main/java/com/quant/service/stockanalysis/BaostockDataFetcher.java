package com.quant.service.stockanalysis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.config.StockAnalysisProperties;
import com.quant.dto.stockanalysis.StockAnalysisRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 调 baostock Python 脚本获取分析 pack 数据。
 *
 * <p>从 {@code StockAnalysisService.fetchPack} 拆出 — 负责进程启动、超时控制、JSON 解析。 失败抛出 RuntimeException，由
 * facade 统一捕获记录 FAILED 状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaostockDataFetcher {

  private final StockAnalysisProperties properties;
  private final ObjectMapper objectMapper;

  /**
   * 执行 baostock pack 命令，返回原始 JSON map。
   *
   * @throws RuntimeException 当进程超时 / 退出码非 0 / 输出无 JSON / 解析失败时抛出
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> fetchPack(String code, StockAnalysisRequest req) {
    try {
      List<String> cmd =
          new ArrayList<>(
              List.of(
                  properties.getPythonCommand(),
                  properties.getPythonScript(),
                  "pack",
                  code,
                  String.valueOf(req.getQuoteDays() == null ? 60 : req.getQuoteDays()),
                  String.valueOf(req.getYears() == null ? 2 : req.getYears())));
      if (Boolean.TRUE.equals(req.getLite())) {
        cmd.add("--lite");
      }
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
      boolean done = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
      if (!done) {
        process.destroyForcibly();
        throw new RuntimeException("baostock 调用超时 (" + properties.getTimeoutSeconds() + "s)");
      }
      if (process.exitValue() != 0) {
        throw new RuntimeException("baostock 退出码 " + process.exitValue() + ": " + stdout);
      }
      String content = stdout.toString();
      int idx = content.indexOf('{');
      if (idx < 0) throw new RuntimeException("baostock 输出无 JSON");
      Map<String, Object> parsed = objectMapper.readValue(content.substring(idx), Map.class);
      return parsed == null ? Collections.emptyMap() : parsed;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("baostock 调用失败: " + e.getMessage(), e);
    }
  }
}
