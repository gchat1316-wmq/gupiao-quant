package com.quant.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "swing-trading")
public class SwingTradingProperties {

  private boolean enabled = true;
  private boolean requireTradingTime = true;
  private String intradayCron = "0 */2 9-15 * * MON-FRI";
  private String eodCron = "0 5 15 * * MON-FRI";
  private int cooldownMinutes = 30;

  /** 量能放大阈值：vol_ma20 / vol_ma60 */
  private BigDecimal volRatioMin = BigDecimal.valueOf(1.30);

  /** 回踩后涨停日量能萎缩上限比例 */
  private BigDecimal pullbackVolMaxRatio = BigDecimal.valueOf(0.50);

  /** 回踩有效交易日 */
  private int pullbackExpireDays = 8;

  /** 平台整理最短/最长交易日 */
  private int platformMinDays = 5;

  private int platformMaxDays = 10;

  /** 突破放量：相对 5 日均量倍数 */
  private BigDecimal breakoutVolMult = BigDecimal.valueOf(1.50);

  /** 突破位缓冲（次日回踩） */
  private BigDecimal breakoutBufferPct = BigDecimal.valueOf(0.005);

  private BigDecimal pullbackHardStopBufferPct = BigDecimal.valueOf(0.02);
  private BigDecimal pullbackSoftStopPct = BigDecimal.valueOf(0.08);
  private BigDecimal breakoutHardStopBufferPct = BigDecimal.valueOf(0.03);
  private BigDecimal breakoutSoftStopPct = BigDecimal.valueOf(0.10);

  private BigDecimal trailTier1Profit = BigDecimal.valueOf(0.15);
  private BigDecimal trailTier2Profit = BigDecimal.valueOf(0.30);
  private BigDecimal trailTier3Profit = BigDecimal.valueOf(0.50);
  private BigDecimal trailDrawdownTier1 = BigDecimal.valueOf(0.08);
  private BigDecimal trailDrawdownTier2 = BigDecimal.valueOf(0.06);
  private BigDecimal trailDrawdownTier3 = BigDecimal.valueOf(0.05);
  private BigDecimal lockedProfitTier2 = BigDecimal.valueOf(0.20);
  private BigDecimal singleDayCrashPct = BigDecimal.valueOf(0.07);

  private BigDecimal addMinProfitPct = BigDecimal.valueOf(0.10);
  private BigDecimal addSizeRatio = BigDecimal.valueOf(0.50);

  private BigDecimal maxSinglePositionPct = BigDecimal.valueOf(15);
  private BigDecimal maxSectorPositionPct = BigDecimal.valueOf(40);
  private int maxOpenPositions = 4;

  private BigDecimal defaultAccountEquity = BigDecimal.valueOf(100_000);
}
