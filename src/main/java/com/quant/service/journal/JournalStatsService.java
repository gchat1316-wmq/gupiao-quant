package com.quant.service.journal;

import com.quant.dto.journal.EquityCurvePoint;
import com.quant.dto.journal.JournalStatsDTO;
import com.quant.dto.journal.RDistributionBucket;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    public List<EquityCurvePoint> equityCurve(JournalTrade.Mode mode) {
        List<JournalTrade> closed = mode == null
                ? repo.findAllClosedOrdered()
                : repo.findClosedByMode(mode);
        BigDecimal cum = BigDecimal.ZERO;
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        List<EquityCurvePoint> out = new ArrayList<>();
        int idx = 1;
        for (JournalTrade t : closed) {
            BigDecimal r = t.getRMultiple() != null ? t.getRMultiple() : BigDecimal.ZERO;
            cum = cum.add(r);
            out.add(new EquityCurvePoint(idx++, t.getId(),
                    t.getExitDate() != null ? t.getExitDate().toLocalDate().format(fmt) : null,
                    cum.setScale(4, RoundingMode.HALF_UP)));
        }
        return out;
    }

    public List<RDistributionBucket> rDistribution(JournalTrade.Mode mode) {
        List<JournalTrade> closed = mode == null
                ? repo.findAllClosedOrdered()
                : repo.findClosedByMode(mode);
        long[] buckets = new long[7];   // <-2, -2~-1, -1~0, 0~1, 1~2, 2~3, >3
        for (JournalTrade t : closed) {
            BigDecimal r = t.getRMultiple() != null ? t.getRMultiple() : BigDecimal.ZERO;
            double rd = r.doubleValue();
            if      (rd < -2) buckets[0]++;
            else if (rd < -1) buckets[1]++;
            else if (rd <  0) buckets[2]++;
            else if (rd <  1) buckets[3]++;
            else if (rd <  2) buckets[4]++;
            else if (rd <  3) buckets[5]++;
            else              buckets[6]++;
        }
        String[] labels = {"<-2R", "-2~-1R", "-1~0R", "0~1R", "1~2R", "2~3R", ">3R"};
        List<RDistributionBucket> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) out.add(new RDistributionBucket(labels[i], buckets[i]));
        return out;
    }
}