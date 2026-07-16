package com.quant.dto.stockanalysis;

import lombok.Data;

@Data
public class StockAnalysisRequest {
  /** 股票代码, 6位数字 (如 688627) 或 sh.688627 */
  private String code;

  /** 分析方法: purple_perilla(紫苏叶) | gaojingqi(高景气九维) | full(两者合并) */
  private String method = "full";

  /** 财务历史年数, 默认2 */
  private Integer years = 2;

  /** 是否 lite 模式 (lite=3维度, 速度快3倍) */
  private Boolean lite = true;

  /** 行情回溯天数, 默认 60 */
  private Integer quoteDays = 60;
}
