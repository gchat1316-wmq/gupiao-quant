package com.quant.dto.techai;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Getter;

/** 策略路线图的一档：描述「如果在这个价位建仓/加仓，之后会怎样」。 watching 状态下按现价+参数预演全部档位，holding 状态下仅展示当前档。 */
@Getter
@Builder
public class StrategyLevelDTO {
  /** 档位标签：首仓 / 加仓1 / 加仓2 / 加仓3 / ... */
  private String label;

  /** 本档买入价 */
  private BigDecimal price;

  /** 本档买入手数 */
  private BigDecimal lots;

  /** 累计持仓手数 */
  private BigDecimal totalLots;

  /** 累计平均成本 */
  private BigDecimal avgCost;

  /** 本档对应的移动止损价（peak×(1-trail%)） */
  private BigDecimal stopPrice;

  /** 止损是否低于成本 */
  private boolean stopBelowCost;
}
