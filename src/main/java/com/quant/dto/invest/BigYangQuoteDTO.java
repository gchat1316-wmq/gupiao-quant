package com.quant.dto.invest;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 大阳线信号实时价格（精简 DTO）。
 *
 * <p>与 {@link BigYangSignalDTO} 解耦：前端先拉基础数据渲染表格，再异步拉 /signals/quotes 拿本 DTO Map 填到
 * 对应行。设计上故意只保留价格相关字段，便于客户端按 {@code stockCode} 拼接。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BigYangQuoteDTO {
  private String stockCode;
  private BigDecimal currentPrice;
  private LocalDate currentPriceDate;
}
