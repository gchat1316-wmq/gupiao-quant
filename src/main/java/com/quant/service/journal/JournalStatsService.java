package com.quant.service.journal;

import com.quant.dto.journal.JournalStatsDTO;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JournalStatsService {

    private final JournalTradeRepository repo;

    public JournalStatsDTO stats(JournalTrade.Mode mode) {
        List<JournalTrade> closed = mode == null
                ? repo.findAllClosedOrdered()
                : repo.findClosedByMode(mode);

        if (closed.isEmpty()) {
            return JournalStatsDTO.builder()
                    .totalTrades(0).wins(0).losses(0)
                    .winRate(BigDecimal.ZERO)
                    .averageR(BigDecimal.ZERO)
                    .averageWinR(BigDecimal.ZERO)
                    .averageLossR(BigDecimal.ZERO)
                    .expectedValue(BigDecimal.ZERO)
                    .maxDrawdown(BigDecimal.ZERO)
                    .longestWinStreak(0L).longestLossStreak(0L)
                    .build();
        }

        int total = closed.size();
        int wins = 0, losses = 0;
        BigDecimal sumR = BigDecimal.ZERO;
        BigDecimal sumWin = BigDecimal.ZERO;
        BigDecimal sumLoss = BigDecimal.ZERO;
        long winStreak = 0, lossStreak = 0, maxWinStreak = 0, maxLossStreak = 0;

        for (JournalTrade t : closed) {
            BigDecimal r = t.getRMultiple() != null ? t.getRMultiple() : BigDecimal.ZERO;
            sumR = sumR.add(r);
            if (r.signum() > 0) {
                wins++; sumWin = sumWin.add(r);
                winStreak++; lossStreak = 0;
                if (winStreak > maxWinStreak) maxWinStreak = winStreak;
            } else if (r.signum() < 0) {
                losses++; sumLoss = sumLoss.add(r);
                lossStreak++; winStreak = 0;
                if (lossStreak > maxLossStreak) maxLossStreak = lossStreak;
            } else {
                winStreak = 0; lossStreak = 0;
            }
        }

        BigDecimal winRate = BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        BigDecimal avgR = sumR.divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        BigDecimal avgWin = wins == 0 ? BigDecimal.ZERO
                : sumWin.divide(BigDecimal.valueOf(wins), 4, RoundingMode.HALF_UP);
        BigDecimal avgLoss = losses == 0 ? BigDecimal.ZERO
                : sumLoss.divide(BigDecimal.valueOf(losses), 4, RoundingMode.HALF_UP);
        BigDecimal lossRate = BigDecimal.ONE.subtract(winRate);
        BigDecimal ev = winRate.multiply(avgWin).add(lossRate.multiply(avgLoss))
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal maxDD = computeMaxDrawdown(closed);

        return JournalStatsDTO.builder()
                .totalTrades(total).wins(wins).losses(losses)
                .winRate(winRate).averageR(avgR)
                .averageWinR(avgWin).averageLossR(avgLoss)
                .expectedValue(ev).maxDrawdown(maxDD)
                .longestWinStreak(maxWinStreak).longestLossStreak(maxLossStreak)
                .build();
    }

    private BigDecimal computeMaxDrawdown(List<JournalTrade> closed) {
        BigDecimal cum = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDD = BigDecimal.ZERO;
        for (JournalTrade t : closed) {
            BigDecimal r = t.getRMultiple() != null ? t.getRMultiple() : BigDecimal.ZERO;
            cum = cum.add(r);
            if (cum.compareTo(peak) > 0) peak = cum;
            BigDecimal dd = cum.subtract(peak);
            if (dd.compareTo(maxDD) < 0) maxDD = dd;
        }
        return maxDD.setScale(4, RoundingMode.HALF_UP);
    }
}