package com.quant.dto.position;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 仓位建议请求:用户准备买入一只股票时提交的输入参数。
 * 后端依据三大公式计算建议仓位、风报比、期望值与回撤预估。
 */
@Data
public class PositionAdviceRequest {

    /** 账户总资金(元) */
    @NotNull
    @Positive
    private BigDecimal accountCapital;

    /** 入场价(元/股) */
    @NotNull
    @DecimalMin(value = "0.0001", inclusive = true)
    private BigDecimal entryPrice;

    /** 止损价(元/股),必须小于入场价 */
    @NotNull
    @DecimalMin(value = "0.0001", inclusive = true)
    private BigDecimal stopLossPrice;

    /** 目标价(元/股),必须大于入场价 */
    @NotNull
    @DecimalMin(value = "0.0001", inclusive = true)
    private BigDecimal targetPrice;

    /** 每笔交易愿意承担的风险比例(账户的百分比),默认 1% */
    @DecimalMin(value = "0.0001", inclusive = true)
    private BigDecimal riskPercent;

    /** 用户对该系统的历史胜率估计(0-1 之间的小数),默认 0.4 = 40% */
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal winRate;

    /** 股票代码/名称,可选,用于报告抬头 */
    private String stockKeyword;
}
