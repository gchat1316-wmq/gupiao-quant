package com.quant.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 东方财富板块资金流拉取服务。
 *
 * <p>数据来源：https://push2.eastmoney.com/api/qt/clist/get 字段映射：
 *
 * <ul>
 *   <li>f12 = 板块代码
 *   <li>f14 = 板块名称
 *   <li>f62 = 主力净流入（元）
 *   <li>f66 = 超大单净流入（元）
 *   <li>f69 = 超大单净占比（%）
 *   <li>f72 = 大单净流入（元）
 *   <li>f75 = 大单净占比（%）
 *   <li>f78 = 中单净流入（元）
 *   <li>f81 = 中单净占比（%）
 *   <li>f84 = 小单净流入（元）
 *   <li>f87 = 小单净占比（%）
 *   <li>f184 = 涨跌幅（%）
 * </ul>
 */
@Slf4j
@Service
public class FundFlowService {

  /** 每侧最多取多少条（流入 + 流出各取这么多） */
  private static final int MAX_PER_SIDE = 30;

  private static final String EM_HOST = "push2delay.eastmoney.com";
  private static final String FIELDS = "f12,f14,f62,f66,f69,f72,f75,f78,f81,f84,f87,f184";

  // 流入（po=1, fid=f62 desc）：主力净流入最多的板块
  private static String urlInflow(int page, int size) {
    return "https://"
        + EM_HOST
        + "/api/qt/clist/get?pn="
        + page
        + "&pz="
        + size
        + "&po=1&np=1"
        + "&fltt=2&invt=2&fid=f62&fs=m:90+t:2+f:!50"
        + "&fields="
        + FIELDS
        + "&ut=b2884a39322259f484a851477f2a9a86";
  }

  // 流出（po=0, fid=f62 asc）：主力净流出最多的板块（负值最多）
  private static String urlOutflow(int page, int size) {
    return "https://"
        + EM_HOST
        + "/api/qt/clist/get?pn="
        + page
        + "&pz="
        + size
        + "&po=0&np=1"
        + "&fltt=2&invt=2&fid=f62&fs=m:90+t:2+f:!50"
        + "&fields="
        + FIELDS
        + "&ut=b2884a39322259f484a851477f2a9a86";
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

  private final QuoteHttpClient httpClient;

  /** 内存缓存：key="sector", value=List<SectorFundFlow> */
  private final AtomicReference<CachedResult> cache =
      new AtomicReference<>(new CachedResult(List.of(), LocalDateTime.MIN));

  public FundFlowService(QuoteHttpClient httpClient) {
    this.httpClient = httpClient;
  }

  /** 返回当前缓存数据（优先命中，不调外部） */
  public FundFlowData getCached() {
    return buildResponse(cache.get());
  }

  /** 强制刷新，从东方财富拉取最新数据 */
  public FundFlowData refresh() {
    List<SectorFundFlow> list = fetchFromEm();
    cache.set(new CachedResult(list, LocalDateTime.now()));
    return buildResponse(cache.get());
  }

  private List<SectorFundFlow> fetchFromEm() {
    try {
      // 并发拉流入 + 流出两份数据
      List<SectorFundFlow> inflow = fetchOnePage(urlInflow(1, MAX_PER_SIDE), true);
      List<SectorFundFlow> outflow = fetchOnePage(urlOutflow(1, MAX_PER_SIDE), false);
      List<SectorFundFlow> combined = new ArrayList<>(inflow.size() + outflow.size());
      combined.addAll(outflow);
      combined.addAll(inflow);
      return combined;
    } catch (Exception e) {
      log.warn("拉取板块资金流失败: {}", e.getMessage());
      return List.of();
    }
  }

  private List<SectorFundFlow> fetchOnePage(String url, boolean isInflow) {
    try {
      String body =
          httpClient.getWithReferer(url, "https://data.eastmoney.com/", StandardCharsets.UTF_8);
      if (body == null || body.isBlank()) {
        return List.of();
      }
      return parse(body, isInflow);
    } catch (Exception e) {
      log.warn("拉取资金流页失败: {}", e.getMessage());
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  private List<SectorFundFlow> parse(String json, boolean isInflow) {
    try {
      Map<String, Object> root = MAPPER.readValue(json, Map.class);
      Map<String, Object> data = (Map<String, Object>) root.get("data");
      if (data == null) return List.of();
      List<Map<String, Object>> diff = (List<Map<String, Object>>) data.get("diff");
      if (diff == null) return List.of();

      List<SectorFundFlow> result = new ArrayList<>(diff.size());
      for (Map<String, Object> m : diff) {
        result.add(
            new SectorFundFlow(
                str(m, "f12"),
                str(m, "f14"),
                yuan(m, "f62"), // 主力净流入
                pct(m, "f69"), // 超大单净占比
                yuan(m, "f66"), // 超大单净流入
                yuan(m, "f72"), // 大单净流入
                pct(m, "f75"), // 大单净占比
                yuan(m, "f78"), // 中单净流入
                yuan(m, "f84"), // 小单净流入
                pct(m, "f184") // 涨跌幅
                ));
      }
      return result;
    } catch (Exception e) {
      log.warn("解析资金流 JSON 失败: {}", e.getMessage());
      return List.of();
    }
  }

  private String str(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v == null ? "" : v.toString();
  }

  /** 将东方财富的元转为"亿"或"万"，保留 2 位小数 */
  private String yuan(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v == null) return "—";
    double val;
    if (v instanceof Number n) {
      val = n.doubleValue();
    } else {
      try {
        val = Double.parseDouble(v.toString());
      } catch (Exception e) {
        return "—";
      }
    }
    if (Math.abs(val) >= 1_0000_0000) {
      return String.format("%.2f亿", val / 1_0000_0000);
    } else if (Math.abs(val) >= 1_0000) {
      return String.format("%.2f万", val / 1_0000);
    } else if (val == 0) {
      return "0";
    } else {
      return String.format("%.0f", val);
    }
  }

  private String pct(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (v == null) return "—";
    double val;
    if (v instanceof Number n) {
      val = n.doubleValue();
    } else {
      try {
        val = Double.parseDouble(v.toString());
      } catch (Exception e) {
        return "—";
      }
    }
    return String.format("%.2f%%", val);
  }

  private FundFlowData buildResponse(CachedResult cached) {
    List<SectorFundFlow> all = cached.items;
    // 流出（负数，绝对值大的排前） → 流入（正数，绝对值大的排前）
    List<SectorFundFlow> sorted =
        all.stream()
            .sorted(
                (a, b) -> {
                  double av = parseYuan(a.mainNet);
                  double bv = parseYuan(b.mainNet);
                  if ((av < 0) != (bv < 0)) {
                    return av < 0 ? -1 : 1; // 负数排前
                  }
                  return Double.compare(Math.abs(bv), Math.abs(av)); // 同符号按绝对值降序
                })
            .toList();
    return new FundFlowData(sorted, cached.updatedAt.format(TIME_FMT), cached.updatedAt);
  }

  private double parseYuan(String str) {
    if (str == null || str.equals("—") || str.isEmpty()) return 0;
    try {
      if (str.endsWith("亿")) return Double.parseDouble(str.replace("亿", "")) * 1e8;
      if (str.endsWith("万")) return Double.parseDouble(str.replace("万", "")) * 1e4;
      return Double.parseDouble(str);
    } catch (Exception e) {
      return 0;
    }
  }

  // ── 数据模型 ────────────────────────────────────────────────────────

  public record SectorFundFlow(
      String code,
      String name,
      String mainNet, // 主力净流入（亿/万格式化）
      String superNetRatio, // 超大单净占比
      String superNet, // 超大单净流入
      String largeNet, // 大单净流入
      String largeNetRatio, // 大单净占比
      String mediumNet, // 中单净流入
      String smallNet, // 小单净流入
      String changePct // 涨跌幅
      ) {}

  public record FundFlowData(
      List<SectorFundFlow> items, String updatedAt, LocalDateTime updatedAtRaw) {}

  private record CachedResult(List<SectorFundFlow> items, LocalDateTime updatedAt) {}
}
