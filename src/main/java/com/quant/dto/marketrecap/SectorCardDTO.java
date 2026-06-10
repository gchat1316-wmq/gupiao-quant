package com.quant.dto.marketrecap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SectorCardDTO {
    private String name;
    private String strengthLabel;
    private List<String> leaders;
    private String catalyst;
}
