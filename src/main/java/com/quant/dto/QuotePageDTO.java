package com.quant.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotePageDTO {
  private List<QuoteDTO> list;
  private long total;
  private int page;
  private int pageSize;
}
