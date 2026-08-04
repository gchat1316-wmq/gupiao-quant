package com.quant.dto.monitor;

import java.util.List;

import lombok.Data;

/**
 * 添加监控的请求体。支持单码 {@code stockCode} 或批量 {@code stockCodes}（也可在 stockCode
 * 里用逗号/换行/空白分隔多码）。poolType 必填。
 */
@Data
public class MonitorAddRequest {
  /** 单只股票代码，或用逗号/换行分隔的多只 */
  private String stockCode;

  /** 批量股票代码列表（优先于 stockCode 解析结果合并） */
  private List<String> stockCodes;

  /** 'tech_ai' | 'potential' | 'invest'（兼容历史别名 'stock'） */
  private String poolType;

  private String stockName;
  private String memo;

  /** 可选：加入时一并写入的监控字段 */
  private java.math.BigDecimal fixedBuyPrice;

  private java.math.BigDecimal fixedSellPrice;
  private Integer fixedBuyEnabled;
  private Integer fixedSellEnabled;
  private java.math.BigDecimal entryPrice;
  private java.math.BigDecimal takeProfitPct;
  private java.math.BigDecimal stopLossPct;
  private String monitorMode;
  private String serverchanTemplate;
}
