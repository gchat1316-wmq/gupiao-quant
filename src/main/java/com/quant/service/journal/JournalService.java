package com.quant.service.journal;

import com.quant.dto.journal.*;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class JournalService {

    private final JournalTradeRepository repo;

    @Transactional
    public JournalTradeDTO create(JournalTradeCreateRequest req, String username) {
        validate(req);
        JournalTrade j = new JournalTrade();
        j.setMode(JournalTrade.Mode.valueOf(req.getMode()));
        j.setStockCode(req.getStockCode());
        j.setStockName(req.getStockName());
        j.setEntryPrice(req.getEntryPrice());
        j.setEntryDate(req.getEntryDate() != null ? req.getEntryDate() : LocalDateTime.now());
        j.setEntryShares(req.getEntryShares());
        j.setAccountAtEntry(req.getAccountAtEntry());
        j.setRiskPercent(req.getRiskPercent());
        j.setStopPrice(req.getStopPrice());
        j.setTargetPrice(req.getTargetPrice());
        j.setInitialRisk(req.getEntryPrice().subtract(req.getStopPrice())
                .setScale(2, RoundingMode.HALF_UP));
        j.setIsOpen(1);
        j.setTags(req.getTags());
        j.setSetupNotes(req.getSetupNotes());
        j.setSource("MANUAL");
        j.setCreatedBy(username);
        return JournalTradeDTO.from(repo.save(j));
    }

    private void validate(JournalTradeCreateRequest req) {
        if (req.getMode() == null
                || (!req.getMode().equals("REAL") && !req.getMode().equals("PAPER"))) {
            throw new IllegalArgumentException("mode 必须为 REAL 或 PAPER");
        }
        if (req.getStockCode() == null || req.getStockCode().isBlank()) {
            throw new IllegalArgumentException("stockCode 必填");
        }
        if (req.getEntryPrice() == null || req.getEntryPrice().signum() <= 0) {
            throw new IllegalArgumentException("entryPrice 必须 > 0");
        }
        if (req.getStopPrice() == null || req.getStopPrice().signum() <= 0) {
            throw new IllegalArgumentException("stopPrice 必须 > 0");
        }
        if (req.getEntryPrice().compareTo(req.getStopPrice()) <= 0) {
            throw new IllegalArgumentException("entryPrice 必须 > stopPrice");
        }
        if (req.getEntryShares() == null || req.getEntryShares() <= 0) {
            throw new IllegalArgumentException("entryShares 必须 > 0");
        }
        if (req.getTargetPrice() != null) {
            BigDecimal reward = req.getTargetPrice().subtract(req.getEntryPrice());
            BigDecimal risk = req.getEntryPrice().subtract(req.getStopPrice());
            if (reward.compareTo(risk.multiply(new BigDecimal("3"))) < 0) {
                throw new IllegalArgumentException(
                        "风险回报比 < 1:3,违反红线 — Minervini 不会进场");
            }
        }
        if (req.getRiskPercent() != null
                && req.getRiskPercent().compareTo(new BigDecimal("0.02")) > 0) {
            throw new IllegalArgumentException(
                    "单笔风险 " + req.getRiskPercent().multiply(new BigDecimal("100"))
                            + "% 超过 2% 红线");
        }
    }
}
