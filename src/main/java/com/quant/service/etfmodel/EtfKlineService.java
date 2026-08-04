package com.quant.service.etfmodel;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.entity.EtfDailyKline;
import com.quant.repository.EtfDailyKlineRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ETF 日 K 拉取与均线计算。
 *
 * <p>数据源：腾讯 fqkline 前复权接口（与 {@code AStockDataQuoteService} 同源），BaoStock 不覆盖场内 ETF 基金，
 * 且 ETF 价格为 3 位小数，故独立存 {@code etf_daily_kline}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EtfKlineService {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final EtfDailyKlineRepository klineRepo;
  private final JdbcTemplate jdbcTemplate;

  /** 均线快照（基于日线收盘价） */
  public record MaSnapshot(
      BigDecimal latestClose,
      LocalDate latestDate,
      BigDecimal ma5,
      BigDecimal ma20,
      BigDecimal ma20Slope,
      BigDecimal rise20Pct) {

    public static MaSnapshot empty() {
      return new MaSnapshot(null, null, null, null, null, null);
    }
  }

  /** 拉取并落库池内全部代码的日 K。单支失败不影响其他。 */
  public void syncDaily(Collection<String> codes, int daysBack) {
    for (String code : codes) {
      try {
        int rows = syncOne(code, daysBack);
        log.info("etf kline sync ok: {} upserted={}", code, rows);
      } catch (Exception e) {
        log.warn("etf kline sync failed: {} err={}", code, e.getMessage());
      }
    }
  }

  int syncOne(String projectCode, int daysBack) throws Exception {
    String tencentCode = toTencentCode(projectCode);
    String url =
        "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param="
            + tencentCode
            + ",day,,,"
            + Math.max(daysBack, 30)
            + ",qfq";
    String body = httpGet(url);
    JsonNode stockNode = MAPPER.readTree(body).path("data").path(tencentCode);
    JsonNode rows = stockNode.path("qfqday");
    if (!rows.isArray() || rows.isEmpty()) {
      rows = stockNode.path("day");
    }
    if (!rows.isArray()) {
      throw new IllegalStateException("no kline data for " + projectCode);
    }
    int upserted = 0;
    for (JsonNode row : rows) {
      // 行格式: [date, open, close, high, low, volume, ...]
      if (!row.isArray() || row.size() < 6) {
        continue;
      }
      LocalDate date = parseDate(row.get(0).asText());
      BigDecimal open = decimal(row.get(1).asText());
      BigDecimal close = decimal(row.get(2).asText());
      BigDecimal high = decimal(row.get(3).asText());
      BigDecimal low = decimal(row.get(4).asText());
      BigDecimal volume = decimal(row.get(5).asText());
      if (date == null || close == null) {
        continue;
      }
      jdbcTemplate.update(
          """
          INSERT INTO etf_daily_kline (stock_code, trade_date, open_price, high_price, low_price, close_price, volume)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          ON DUPLICATE KEY UPDATE open_price = VALUES(open_price), high_price = VALUES(high_price),
            low_price = VALUES(low_price), close_price = VALUES(close_price), volume = VALUES(volume)
          """,
          normalize(projectCode),
          date,
          open,
          high,
          low,
          close,
          volume == null ? null : volume.longValue());
      upserted++;
    }
    return upserted;
  }

  /** 读取库内最近 60 日 K，计算 MA5/MA20/斜率/近20日涨幅。 */
  public MaSnapshot maSnapshot(String projectCode) {
    List<EtfDailyKline> desc = klineRepo.findTop60ByStockCodeOrderByTradeDateDesc(normalize(projectCode));
    if (desc == null || desc.isEmpty()) {
      return MaSnapshot.empty();
    }
    List<EtfDailyKline> asc = new ArrayList<>(desc);
    asc.sort(Comparator.comparing(EtfDailyKline::getTradeDate));
    int n = asc.size();
    EtfDailyKline latest = asc.get(n - 1);
    BigDecimal ma5 = sma(asc, 5);
    BigDecimal ma20 = sma(asc, 20);
    BigDecimal ma20Prev5 = n > 5 ? sma(asc.subList(0, n - 5), 20) : null;
    BigDecimal slope = null;
    if (ma20 != null && ma20Prev5 != null && ma20Prev5.compareTo(BigDecimal.ZERO) > 0) {
      slope = ma20.subtract(ma20Prev5).divide(ma20Prev5, 6, RoundingMode.HALF_UP);
    }
    BigDecimal rise20 = null;
    if (n >= 21) {
      BigDecimal base = asc.get(n - 21).getClosePrice();
      BigDecimal close = latest.getClosePrice();
      if (base != null && close != null && base.compareTo(BigDecimal.ZERO) > 0) {
        rise20 =
            close
                .subtract(base)
                .divide(base, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
      }
    }
    return new MaSnapshot(latest.getClosePrice(), latest.getTradeDate(), ma5, ma20, slope, rise20);
  }

  private static BigDecimal sma(List<EtfDailyKline> asc, int period) {
    if (asc == null || asc.size() < period || period <= 0) {
      return null;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = asc.size() - period; i < asc.size(); i++) {
      BigDecimal c = asc.get(i).getClosePrice();
      if (c == null) {
        return null;
      }
      sum = sum.add(c);
    }
    return sum.divide(BigDecimal.valueOf(period), 3, RoundingMode.HALF_UP);
  }

  /** "513100.SH" → "sh513100" */
  static String toTencentCode(String projectCode) {
    String normalized = normalize(projectCode);
    int dot = normalized.indexOf('.');
    if (dot < 0) {
      return normalized.toLowerCase();
    }
    return normalized.substring(dot + 1).toLowerCase() + normalized.substring(0, dot);
  }

  /** 统一为大写带后缀格式 "513100.SH" */
  static String normalize(String code) {
    return code == null ? "" : code.trim().toUpperCase();
  }

  private String httpGet(String url) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(6000);
    conn.setReadTimeout(10000);
    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
    conn.setRequestProperty("Referer", "https://stockapp.finance.qq.com/");
    conn.connect();
    int status = conn.getResponseCode();
    InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
    String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    if (status >= 400) {
      throw new IllegalStateException("HTTP " + status + ": " + body);
    }
    return body;
  }

  private static BigDecimal decimal(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (Exception e) {
      return null;
    }
  }
}
