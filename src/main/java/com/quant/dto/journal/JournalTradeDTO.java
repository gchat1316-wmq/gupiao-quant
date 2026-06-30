package com.quant.dto.journal;

import com.quant.entity.JournalTrade;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class JournalTradeDTO {
    private Long id;
    private String mode;
    private String stockCode;
    private String stockName;
    private BigDecimal entryPrice;
    private LocalDateTime entryDate;
    private Integer entryShares;
    private BigDecimal accountAtEntry;
    private BigDecimal riskPercent;
    private BigDecimal stopPrice;
    private BigDecimal targetPrice;
    private BigDecimal exitPrice;
    private LocalDateTime exitDate;
    private String exitReason;
    private BigDecimal initialRisk;
    private BigDecimal pnlAmount;
    private BigDecimal rMultiple;
    private Boolean isOpen;
    private String tags;
    private String setupNotes;
    private String reviewNotes;
    private String source;
    private Long sourceRefId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static JournalTradeDTO from(JournalTrade j) {
        if (j == null) return null;
        return JournalTradeDTO.builder()
                .id(j.getId())
                .mode(j.getMode() != null ? j.getMode().name() : null)
                .stockCode(j.getStockCode())
                .stockName(j.getStockName())
                .entryPrice(j.getEntryPrice())
                .entryDate(j.getEntryDate())
                .entryShares(j.getEntryShares())
                .accountAtEntry(j.getAccountAtEntry())
                .riskPercent(j.getRiskPercent())
                .stopPrice(j.getStopPrice())
                .targetPrice(j.getTargetPrice())
                .exitPrice(j.getExitPrice())
                .exitDate(j.getExitDate())
                .exitReason(j.getExitReason() != null ? j.getExitReason().name() : null)
                .initialRisk(j.getInitialRisk())
                .pnlAmount(j.getPnlAmount())
                .rMultiple(j.getRMultiple())
                .isOpen(j.getIsOpen() != null && j.getIsOpen() == 1)
                .tags(j.getTags())
                .setupNotes(j.getSetupNotes())
                .reviewNotes(j.getReviewNotes())
                .source(j.getSource())
                .sourceRefId(j.getSourceRefId())
                .createdAt(j.getCreatedAt())
                .updatedAt(j.getUpdatedAt())
                .build();
    }
}