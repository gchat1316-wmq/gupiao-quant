package com.quant.service.prosperitystrong;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.config.ProsperityStrongProperties;
import com.quant.entity.ProsperityHotSector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Step 1: 板块扫描
 *
 * <p>主源: 东方财富板块涨幅排行 http://push2.eastmoney.com/api/qt/clist/get?fs=m:90+t:2&fields=...
 *
 * <p>失败兜底: 返回内置常见热点板块作为占位（避免流水线中断）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotSectorScanner {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String EM_URL =
      "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=30&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281"
          + "&fltt=2&invt=2&fid=f3&fs=m:90+t:2"
          + "&fields=f12,f14,f2,f3,f62,f128,f136";

  private static final String A_STOCK_DATA_URL =
      "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=100&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281"
          + "&fltt=2&invt=2&fid=f3&fs=m:90+t:2"
          + "&fields=f2,f3,f4,f12,f13,f14,f62,f104,f105,f128,f136,f140,f141,f207";

  private final ProsperityStrongProperties props;

  public List<ProsperityHotSector> scan(LocalDate snapDate) {
    return scan(snapDate, null);
  }

  public List<ProsperityHotSector> scan(LocalDate snapDate, String provider) {
    List<ProsperityHotSector> result = new ArrayList<>();
    String source =
        "a_stock_data".equalsIgnoreCase(provider) ? "a_stock_data" : props.getSource().getSector();
    if ("a_stock_data".equalsIgnoreCase(source)) {
      result = tryFetch("a_stock_data", snapDate);
      if (result.isEmpty()) {
        result = tryFetch("eastmoney", snapDate);
      }
    } else if ("eastmoney".equalsIgnoreCase(source)) {
      result = tryFetch("eastmoney", snapDate);
    }
    if (result.isEmpty()) {
      result = mockSectors(snapDate);
    }

    result.sort(
        Comparator.comparing(
            ProsperityHotSector::getScore, Comparator.nullsLast(Comparator.reverseOrder())));

    int limit = Math.max(1, props.getMaxSectors());
    if (result.size() > limit) {
      result = new ArrayList<>(result.subList(0, limit));
    }
    for (int i = 0; i < result.size(); i++) {
      result.get(i).setRankNo(i + 1);
    }
    return result;
  }

  private List<ProsperityHotSector> tryFetch(String source, LocalDate snapDate) {
    try {
      return fetchFromSource(source, snapDate);
    } catch (Exception e) {
      log.warn("{} 板块抓取失败: {}", source, e.getMessage());
      return List.of();
    }
  }

  private List<ProsperityHotSector> fetchFromSource(String source, LocalDate snapDate)
      throws Exception {
    String body = fetchSectorBody(source);
    return parseSectorBody(source, snapDate, body);
  }

  protected String fetchSectorBody(String source) throws Exception {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    int timeoutMs = props.getSource().getTimeoutSeconds() * 1000;
    factory.setConnectTimeout(timeoutMs);
    factory.setReadTimeout(timeoutMs);
    RestTemplate rest = new RestTemplate(factory);

    String url = "a_stock_data".equalsIgnoreCase(source) ? A_STOCK_DATA_URL : EM_URL;
    return rest.getForObject(URI.create(url), String.class);
  }

  private List<ProsperityHotSector> parseSectorBody(String source, LocalDate snapDate, String body)
      throws Exception {
    if (body == null) throw new IllegalStateException("EastMoney 返回为空");
    JsonNode root = MAPPER.readTree(body);
    JsonNode diff = root.path("data").path("diff");
    if (!diff.isArray() || diff.isEmpty()) {
      throw new IllegalStateException("EastMoney 数据为空: " + body);
    }

    List<ProsperityHotSector> list = new ArrayList<>();
    int rank = 0;
    for (JsonNode item : diff) {
      String code = item.path("f12").asText("");
      String name = item.path("f14").asText("");
      if (code.isEmpty() || name.isEmpty()) continue;
      BigDecimal change1d = bd(item.path("f3"));
      BigDecimal inflow5d = bd(item.path("f62"));
      BigDecimal leadStockChange = bd(item.path("f136"));

      ProsperityHotSector e = new ProsperityHotSector();
      e.setSnapDate(snapDate);
      e.setSectorCode(code);
      e.setSectorName(name);
      e.setRankNo(++rank);
      e.setChange1d(change1d);
      e.setCapitalInflow5d(inflow5d);
      e.setUpCount(intOrNull(item.path("f104")));
      e.setDownCount(intOrNull(item.path("f105")));
      e.setLeadStock(textOrNull(item.path("f140")));
      e.setLeadStockChange(leadStockChange);
      // 5d/20d 在该接口无,后续扩展用专用接口补; persistence_days 同
      e.setScore(
          estimateScore(change1d, inflow5d, e.getUpCount(), e.getDownCount(), leadStockChange));
      e.setDataSource("a_stock_data".equalsIgnoreCase(source) ? "a_stock_data" : "eastmoney");
      list.add(e);
    }
    return list;
  }

  private BigDecimal estimateScore(
      BigDecimal change1d,
      BigDecimal inflow5d,
      Integer upCount,
      Integer downCount,
      BigDecimal leadStockChange) {
    double s1 = change1d == null ? 0 : Math.min(60, Math.max(-20, change1d.doubleValue())) + 20;
    double s2 =
        inflow5d == null
            ? 0
            : Math.signum(inflow5d.doubleValue())
                * Math.min(40, Math.log10(Math.abs(inflow5d.doubleValue()) / 1e8 + 1) * 20);
    double breadth = 50;
    if (upCount != null && downCount != null && upCount + downCount > 0) {
      breadth = upCount * 100.0 / (upCount + downCount);
    }
    double leader =
        leadStockChange == null
            ? 50
            : Math.min(100, Math.max(0, leadStockChange.doubleValue() * 5 + 50));
    double score = 0.4 * s1 + 0.3 * (s2 + 40) + 0.2 * breadth + 0.1 * leader;
    score = Math.max(0, Math.min(100, score));
    return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal bd(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) return null;
    if (!n.isNumber()) {
      String s = n.asText("").trim();
      if (s.isEmpty() || "-".equals(s)) return null;
      try {
        return new BigDecimal(s);
      } catch (Exception e) {
        return null;
      }
    }
    return BigDecimal.valueOf(n.asDouble());
  }

  private Integer intOrNull(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) return null;
    if (n.isInt() || n.isLong()) return n.asInt();
    String s = n.asText("").trim();
    if (s.isEmpty() || "-".equals(s)) return null;
    try {
      return Integer.parseInt(s);
    } catch (Exception e) {
      return null;
    }
  }

  private String textOrNull(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) return null;
    String s = n.asText("").trim();
    return s.isEmpty() || "-".equals(s) ? null : s;
  }

  /** 兜底: 常见热点板块占位,后续可由用户手动覆盖 */
  private List<ProsperityHotSector> mockSectors(LocalDate snapDate) {
    String[] names = {"半导体", "光模块", "AI算力", "工业母机", "创新药"};
    List<ProsperityHotSector> list = new ArrayList<>();
    for (int i = 0; i < names.length; i++) {
      ProsperityHotSector e = new ProsperityHotSector();
      e.setSnapDate(snapDate);
      e.setSectorCode("MOCK_" + i);
      e.setSectorName(names[i]);
      e.setRankNo(i + 1);
      e.setChange1d(BigDecimal.valueOf(2.5 - i * 0.3));
      e.setChange5d(BigDecimal.valueOf(8.0 - i * 0.8));
      e.setChange20d(BigDecimal.valueOf(15.0 - i * 1.5));
      e.setCapitalInflow5d(BigDecimal.valueOf(5e8 - i * 5e7));
      e.setPersistenceDays(7 - i);
      e.setScore(BigDecimal.valueOf(85 - i * 5));
      e.setDataSource("mock");
      list.add(e);
    }
    return list;
  }
}
