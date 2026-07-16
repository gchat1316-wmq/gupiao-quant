package com.quant.dto.xieboinvest;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 谢博投资 · 每周重点股票（3×3 卡片）单格 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class XieboWeeklyOpportunitySlotDTO {
  private String poolType;
  private Integer slotIndex;
  private String stockCode;
  private String stockName;
  private String reason;
  private LocalDateTime updatedAt;
}
