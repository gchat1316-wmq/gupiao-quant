package com.quant.entity;

import java.io.Serializable;
import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** invest_position_common 复合主键：(pool_type, stock_code)。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InvestPositionCommonId implements Serializable {

  private String stockCode;
  private String poolType;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof InvestPositionCommonId that)) return false;
    return Objects.equals(stockCode, that.stockCode) && Objects.equals(poolType, that.poolType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(stockCode, poolType);
  }
}
