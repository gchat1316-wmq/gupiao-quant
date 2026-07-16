package com.quant.service.aistockdata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.invest.InvestForecastProvider;

import lombok.extern.slf4j.Slf4j;

/**
 * 营收预测 Provider —— 直接从本地 {@code trade_stock_financial} 季度表聚合。
 *
 * <p>思路：股票池里的"科技风投"类型条目基本都有完整的季度财务数据，但预测字段常年为空。 这里取最近 4 个季度的累计营收趋势，按几何 CAGR 外推 Y0/Y1/Y2 三个预测年份。
 * 历史营收和 Q1 财务指标也顺手从同一张表回填（InvestPoolRefreshService 会调过来）。
 *
 * <p>单位换算：{@code trade_stock_financial.revenue} 单位是"元"，股票池字段单位是"亿元"， 这里统一除以 1e8 输出，保留两位小数。
 */
@Slf4j
@Service
public class AStockDataForecastProvider implements InvestForecastProvider {

  private static final BigDecimal ONE_YI = BigDecimal.valueOf(100_000_000L);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);
  private static final BigDecimal MAX_SANE_GROWTH = BigDecimal.valueOf(500L);
  private static final BigDecimal MIN_SANE_GROWTH = BigDecimal.valueOf(-100L);

  private final TradeStockFinancialRepository financialRepository;

  public AStockDataForecastProvider(TradeStockFinancialRepository financialRepository) {
    this.financialRepository = financialRepository;
  }

  @Override
  public Optional<RevenueForecast> fetchRevenueForecast(InvestStockPool pool) {
    FinancialSnapshot snapshot = loadFinancialSnapshot(pool.getStockCode());
    if (snapshot == null) {
      return Optional.empty();
    }
    RevenueForecast forecast = estimateForecast(snapshot);
    if (forecast == null) {
      return Optional.empty();
    }
    return Optional.of(forecast);
  }

  /** 取该股票最近的财务快照：最新 Q 报告期、其毛利率/净利率/营收同比，加上最近 4 个季度的累计营收。 */
  public FinancialSnapshot loadFinancialSnapshot(String stockCode) {
    List<TradeStockFinancial> recent =
        financialRepository.findByStockCodeOrderByReportDateDesc(stockCode);
    if (recent == null || recent.isEmpty()) {
      return null;
    }
    TradeStockFinancial latest = recent.get(0);
    return new FinancialSnapshot(stockCode, latest, recent);
  }

  /**
   * 用最近 4 个累计营收做几何 CAGR 外推。 - 趋势样本不足时回退到"最新同比 * 上一累计营收" - 都没有时返回 null（交给上层不要硬填）
   *
   * <p>注意：{@code trade_stock_financial.revenue_yoy} 单位是 %（例如 67.17 表示 67.17%）， 历史脏数据里偶尔会有
   * 999999.9999 这种异常值（换季/收购），这里要过滤掉。
   */
  private RevenueForecast estimateForecast(FinancialSnapshot snapshot) {
    List<TradeStockFinancial> history = snapshot.history();
    if (history.size() < 2) {
      return null;
    }
    BigDecimal latestCumRevenueYi = toYi(history.get(0).getRevenue());
    if (latestCumRevenueYi == null) {
      return null;
    }

    // 最近 3 个同比（CAGR 用 3 期更稳健）。yoy 字段是百分比，过滤掉异常值
    double[] yoys = new double[3];
    int yoyCount = 0;
    for (int i = 0; i < Math.min(3, history.size()); i++) {
      BigDecimal y = history.get(i).getRevenueYoy();
      if (y != null && isSaneGrowth(y)) {
        yoys[yoyCount++] = y.doubleValue();
      }
    }
    double cagr = 0.0;
    if (yoyCount > 0) {
      double sum = 0;
      for (int i = 0; i < yoyCount; i++) {
        sum += yoys[i];
      }
      cagr = sum / yoyCount / 100.0;
    }
    // 钳制到合理区间，避免单季度异常把预测带飞
    if (cagr < -0.5) cagr = -0.5;
    if (cagr > 1.5) cagr = 1.5;

    BigDecimal y0 = scale(latestCumRevenueYi.multiply(BigDecimal.valueOf(1.0 + cagr)));
    BigDecimal y1 = scale(y0.multiply(BigDecimal.valueOf(1.0 + cagr)));
    BigDecimal y2 = scale(y1.multiply(BigDecimal.valueOf(1.0 + cagr)));
    return new RevenueForecast(y0, y1, y2);
  }

  /** 同比是否在合理区间（-100% ~ +500%），过滤脏数据。 */
  public static boolean isSaneGrowth(BigDecimal yoy) {
    return yoy.compareTo(MIN_SANE_GROWTH) >= 0 && yoy.compareTo(MAX_SANE_GROWTH) <= 0;
  }

  /** 元 → 亿元，四舍五入保留两位小数 */
  public static BigDecimal toYi(BigDecimal yuan) {
    if (yuan == null) return null;
    return yuan.divide(ONE_YI, 2, RoundingMode.HALF_UP);
  }

  private BigDecimal scale(BigDecimal v) {
    return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
  }

  /** 财报快照 —— 给上层 RefreshService 用，绕过 forecast 接口也能拿到原始财务数据。 */
  public record FinancialSnapshot(
      String stockCode, TradeStockFinancial latest, List<TradeStockFinancial> history) {

    /** 当前 Q 报告期年份（用于决定写入哪一年的历史营收）。 */
    public int latestYear() {
      return latest.getReportDate().getYear();
    }

    /** 当前 Q 报告期是否年报（12 月） */
    public boolean isAnnualReport() {
      return latest.getReportDate().getMonthValue() == 12;
    }

    /** 当前季度营收同比 (%) —— 字段单位已是百分比 */
    public BigDecimal latestRevenueYoy() {
      BigDecimal yoy = latest.getRevenueYoy();
      if (yoy == null) {
        return null;
      }
      if (!isSaneGrowth(yoy)) {
        return null;
      }
      return yoy.setScale(2, RoundingMode.HALF_UP);
    }

    /** 最近 Q 的毛利率 (%) */
    public BigDecimal latestGrossMargin() {
      return latest.getGrossMargin();
    }

    /** 最近 Q 的净利率 (%) */
    public BigDecimal latestNetMargin() {
      return latest.getNetMargin();
    }

    /** 从历史里挑出指定年份的 12-31 财报，作为"全年营收"。 */
    public BigDecimal annualRevenueYi(int year) {
      for (TradeStockFinancial f : history) {
        if (f.getReportDate().getYear() == year && f.getReportDate().getMonthValue() == 12) {
          return toYi(f.getRevenue());
        }
      }
      return null;
    }
  }
}
