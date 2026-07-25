package com.quant.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "trend-wave")
public class TrendWaveProperties {

  private boolean enabled = true;
  private boolean requireTradingTime = true;
  private String intradayCron = "0 */1 9-15 * * MON-FRI";
  private String eodCron = "0 10 15 * * MON-FRI";
  private int cooldownMinutes = 30;
  private int maxActiveWatches = 4;
  private int maxPositions = 4;
  private BigDecimal maxSinglePositionPct = BigDecimal.valueOf(15);
  private BigDecimal maxSectorPositionPct = BigDecimal.valueOf(40);
  private int buySignalExpireDays = 3;
  private int pauseDaysAfterTwoStops = 2;

  /** 大盘指数代码（腾讯行情），用于仓位环境判断 */
  private String marketIndexCode = "000001.SH";

  private List<String> sectorWhitelist = new ArrayList<>(List.of("AI", "算力", "半导体", "芯片", "机器人", "人形机器人", "光模块", "CPO", "存储", "GPU", "先进封装"));

  private Screening screening = new Screening();
  private Pullback pullback = new Pullback();
  private Breakout breakout = new Breakout();
  private StopLoss stopLoss = new StopLoss();
  private TakeProfit takeProfit = new TakeProfit();
  private AddPosition addPosition = new AddPosition();

  @Data
  public static class Screening {
    private BigDecimal volumeExpandRatio = BigDecimal.valueOf(1.30);
    private BigDecimal highNear3yRatio = BigDecimal.valueOf(0.85);
    private BigDecimal peSoftCap = BigDecimal.valueOf(120);
    private BigDecimal minMarketCapYi = BigDecimal.valueOf(50);
    private boolean requireSectorTag = false;
  }

  @Data
  public static class Pullback {
    private int minLimitUp = 1;
    private int maxLimitUp = 2;
    private int lookbackDays = 20;
    private BigDecimal shrinkVolumeRatio = BigDecimal.valueOf(0.50);
    private BigDecimal volumeDumpRatio = BigDecimal.valueOf(1.20);
  }

  @Data
  public static class Breakout {
    private int minPlatformDays = 5;
    private int maxPlatformDays = 10;
    private BigDecimal volumeRatio = BigDecimal.valueOf(1.50);
    private BigDecimal rangeTightenPct = BigDecimal.valueOf(8);
  }

  @Data
  public static class StopLoss {
    private BigDecimal pullbackPrimaryBufferPct = BigDecimal.valueOf(2);
    private BigDecimal pullbackSecondaryPct = BigDecimal.valueOf(8);
    private BigDecimal breakoutPrimaryBufferPct = BigDecimal.valueOf(3);
    private BigDecimal breakoutSecondaryPct = BigDecimal.valueOf(10);
    private int belowMa20ConfirmDays = 2;
    private BigDecimal intradayVolumeBreakRatio = BigDecimal.valueOf(1.0);
  }

  @Data
  public static class TakeProfit {
    private BigDecimal tier1ProfitPct = BigDecimal.valueOf(15);
    private BigDecimal tier2ProfitPct = BigDecimal.valueOf(30);
    private BigDecimal tier3ProfitPct = BigDecimal.valueOf(50);
    private BigDecimal tier1DrawdownPct = BigDecimal.valueOf(8);
    private BigDecimal tier2DrawdownPct = BigDecimal.valueOf(6);
    private BigDecimal tier3DrawdownPct = BigDecimal.valueOf(5);
    private BigDecimal flashCrashPct = BigDecimal.valueOf(7);
    private BigDecimal flashCrashVolRatio = BigDecimal.valueOf(2.0);
    private BigDecimal firstSellPct = BigDecimal.valueOf(50);
  }

  @Data
  public static class AddPosition {
    private BigDecimal minProfitPct = BigDecimal.valueOf(10);
    private BigDecimal maxAddRatio = BigDecimal.valueOf(0.50);
  }
}
