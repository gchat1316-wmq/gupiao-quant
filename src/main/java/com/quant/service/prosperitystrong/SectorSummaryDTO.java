package com.quant.service.prosperitystrong;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * sectors 列表接口的轻量投影。
 *
 * <p>不包含 aiNarrative (TEXT 字段)，避免 InnoDB off-page 读导致 sectors 接口慢 1-2s。 详情接口或单独取 aiNarrative。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SectorSummaryDTO implements Serializable {
  private Integer id;
  private LocalDate snapDate;
  private String sectorCode;
  private String sectorName;
  private Integer rankNo;
  private BigDecimal change1d;
  private BigDecimal change5d;
  private BigDecimal change20d;
  private BigDecimal capitalInflow5d;
  private Integer upCount;
  private Integer downCount;
  private String leadStock;
  private BigDecimal leadStockChange;
  private Integer persistenceDays;
  private BigDecimal score;
  private String dataSource;
}
