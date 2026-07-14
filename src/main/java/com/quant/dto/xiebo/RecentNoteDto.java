package com.quant.dto.xiebo;

import com.quant.entity.InvestXieboStockNote;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
public class RecentNoteDto {
    private String stockCode;
    private String noteHtml;
    private LocalDateTime updatedAt;

    public static RecentNoteDto of(InvestXieboStockNote e) {
        if (e == null) return null;
        return RecentNoteDto.builder()
                .stockCode(e.getStockCode())
                .noteHtml(e.getNoteHtml())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
