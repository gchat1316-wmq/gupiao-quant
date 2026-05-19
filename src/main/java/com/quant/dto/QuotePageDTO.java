package com.quant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
