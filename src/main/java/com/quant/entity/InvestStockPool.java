package com.quant.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 龙江投资股票池（估值/营收预测/景气指标）。
 *
 * <p>持仓/告警字段已迁至 {@link InvestPositionCommon}（pool_type = 'invest'）。 服务层通过 {@link
 * com.quant.repository.InvestPositionCommonRepository} 读写持仓数据。
 *
 * @see InvestPositionCommon
 */
@Getter
@Setter
@Entity
@Table(name = "invest_stock_pool")
public class InvestStockPool {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(name = "stock_code", nullable = false, length = 20, unique = true)
  private String stockCode;

  @Column(name = "stock_name", length = 255)
  private String stockName;

  @Column(name = "pool_type", nullable = false, length = 20)
  private String poolType;

  // ===== 估值价格（三段估值） =====
  @Column(name = "undervalued_price", precision = 10, scale = 2)
  private BigDecimal undervaluedPrice;

  @Column(name = "fair_price", precision = 10, scale = 2)
  private BigDecimal fairPrice;

  @Column(name = "overvalued_price", precision = 10, scale = 2)
  private BigDecimal overvaluedPrice;

  @Column(name = "target_buy_price", precision = 10, scale = 2)
  private BigDecimal targetBuyPrice;

  // ===== 营收预测 =====
  @Column(name = "revenue_forecast_y0", precision = 10, scale = 2)
  private BigDecimal revenueForecastY0;

  @Column(name = "revenue_forecast_y1", precision = 10, scale = 2)
  private BigDecimal revenueForecastY1;

  @Column(name = "revenue_forecast_y2", precision = 10, scale = 2)
  private BigDecimal revenueForecastY2;

  @Column(name = "rev_2023", precision = 10, scale = 2)
  private BigDecimal revenue2023;

  @Column(name = "rev_2024", precision = 10, scale = 2)
  private BigDecimal revenue2024;

  @Column(name = "rev_2025", precision = 10, scale = 2)
  private BigDecimal revenue2025;

  // ===== 10 倍 PS 看板 =====
  @Column(name = "q1_gross_margin", precision = 8, scale = 2)
  private BigDecimal q1GrossMargin;

  @Column(name = "q1_net_margin", precision = 8, scale = 2)
  private BigDecimal q1NetMargin;

  @Column(name = "q1_revenue_growth", precision = 8, scale = 2)
  private BigDecimal q1RevenueGrowth;

  @Column(name = "min_ps_5y", precision = 10, scale = 2)
  private BigDecimal minPs5y;

  @Column(name = "target_market_cap", precision = 12, scale = 2)
  private BigDecimal targetMarketCap;

  @Column(name = "current_market_cap", precision = 12, scale = 2)
  private BigDecimal currentMarketCap;

  @Column(name = "ytd_gain_pct", precision = 8, scale = 2)
  private BigDecimal ytdGainPct;

  // ===== 展示顺序与刷新时间 =====
  @Column(name = "display_order")
  private Integer displayOrder;

  @Column(name = "pool_data_updated_at")
  private LocalDateTime poolDataUpdatedAt;

  @Column(name = "pool_update_error", length = 1000)
  private String poolUpdateError;

  // ===== 景气分析（来自 prosperity） =====
  @Column(name = "profit_level", length = 20)
  private String profitLevel;

  @Column(name = "valuation_range", length = 20)
  private String valuationRange;

  @Column(name = "memo", columnDefinition = "TEXT")
  private String memo;

  @Column(name = "target_price", precision = 10, scale = 2)
  private BigDecimal targetPrice;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
