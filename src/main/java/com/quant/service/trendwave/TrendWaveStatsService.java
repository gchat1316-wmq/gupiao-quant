package com.quant.service.trendwave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.dto.trendwave.MoneyPositionDTO;
import com.quant.dto.trendwave.MoneyStatsDTO;
import com.quant.entity.MoneyPosition;
import com.quant.entity.MoneyWatch;
import com.quant.repository.MoneyPositionRepository;
import com.quant.repository.MoneyWatchRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TrendWaveStatsService {

  private final MoneyPositionRepository positionRepository;
  private final MoneyWatchRepository watchRepository;

  @Transactional(readOnly = true)
  public MoneyStatsDTO stats(Long userId) {
    List<MoneyPosition> closed =
        positionRepository.findByUserIdAndStatusInOrderByUpdatedAtDesc(userId, List.of("CLOSED"));
    long wins = 0;
    long losses = 0;
    BigDecimal winSum = BigDecimal.ZERO;
    BigDecimal lossSum = BigDecimal.ZERO;
    BigDecimal totalPnl = BigDecimal.ZERO;
    Map<String, Agg> byBuy = new HashMap<>();
    Map<String, Agg> bySector = new HashMap<>();

    List<MoneyPositionDTO> recent = new ArrayList<>();
    for (MoneyPosition p : closed) {
      BigDecimal pct = p.getRealizedPnlPct() == null ? BigDecimal.ZERO : p.getRealizedPnlPct();
      BigDecimal pnl = p.getRealizedPnl() == null ? BigDecimal.ZERO : p.getRealizedPnl();
      totalPnl = totalPnl.add(pnl);
      boolean win = pct.compareTo(BigDecimal.ZERO) > 0;
      if (win) {
        wins++;
        winSum = winSum.add(pct);
      } else if (pct.compareTo(BigDecimal.ZERO) < 0) {
        losses++;
        lossSum = lossSum.add(pct.abs());
      }
      byBuy.computeIfAbsent(nullTo(p.getBuyType(), "UNKNOWN"), k -> new Agg()).add(pct, win);
      String sector = "UNKNOWN";
      MoneyWatch w = watchRepository.findById(p.getWatchId()).orElse(null);
      if (w != null && w.getSectorTag() != null && !w.getSectorTag().isBlank()) {
        sector = w.getSectorTag();
      }
      bySector.computeIfAbsent(sector, k -> new Agg()).add(pct, win);
      if (recent.size() < 20) {
        recent.add(toDto(p));
      }
    }

    long total = closed.size();
    BigDecimal winRate =
        total == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    BigDecimal avgWin =
        wins == 0
            ? BigDecimal.ZERO
            : winSum.divide(BigDecimal.valueOf(wins), 2, RoundingMode.HALF_UP);
    BigDecimal avgLoss =
        losses == 0
            ? BigDecimal.ZERO
            : lossSum.divide(BigDecimal.valueOf(losses), 2, RoundingMode.HALF_UP);
    BigDecimal pf =
        avgLoss.compareTo(BigDecimal.ZERO) == 0
            ? (wins > 0 ? BigDecimal.valueOf(999) : BigDecimal.ZERO)
            : avgWin.divide(avgLoss, 2, RoundingMode.HALF_UP);
    BigDecimal expectancy =
        total == 0
            ? BigDecimal.ZERO
            : winRate
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                .multiply(avgWin)
                .subtract(
                    BigDecimal.ONE
                        .subtract(winRate.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
                        .multiply(avgLoss))
                .setScale(2, RoundingMode.HALF_UP);

    return MoneyStatsDTO.builder()
        .totalTrades(total)
        .winTrades(wins)
        .lossTrades(losses)
        .winRate(winRate)
        .avgWinPct(avgWin)
        .avgLossPct(avgLoss)
        .profitFactor(pf)
        .expectancyPct(expectancy)
        .totalRealizedPnl(totalPnl.setScale(2, RoundingMode.HALF_UP))
        .byBuyType(toGroup(byBuy))
        .bySector(toGroup(bySector))
        .recentClosed(recent)
        .build();
  }

  private Map<String, MoneyStatsDTO.GroupStats> toGroup(Map<String, Agg> src) {
    Map<String, MoneyStatsDTO.GroupStats> out = new HashMap<>();
    src.forEach(
        (k, a) ->
            out.put(
                k,
                MoneyStatsDTO.GroupStats.builder()
                    .count(a.count)
                    .wins(a.wins)
                    .winRate(
                        a.count == 0
                            ? BigDecimal.ZERO
                            : BigDecimal.valueOf(a.wins)
                                .divide(BigDecimal.valueOf(a.count), 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP))
                    .avgPnlPct(
                        a.count == 0
                            ? BigDecimal.ZERO
                            : a.sumPct.divide(BigDecimal.valueOf(a.count), 2, RoundingMode.HALF_UP))
                    .build()));
    return out;
  }

  private MoneyPositionDTO toDto(MoneyPosition p) {
    return MoneyPositionDTO.builder()
        .id(p.getId())
        .watchId(p.getWatchId())
        .stockCode(p.getStockCode())
        .stockName(p.getStockName())
        .buyType(p.getBuyType())
        .entryPrice(p.getEntryPrice())
        .entryDate(p.getEntryDate())
        .entryShares(p.getEntryShares())
        .positionPct(p.getPositionPct())
        .peakPrice(p.getPeakPrice())
        .profitTier(p.getProfitTier())
        .status(p.getStatus())
        .closeReason(p.getCloseReason())
        .realizedPnl(p.getRealizedPnl())
        .realizedPnlPct(p.getRealizedPnlPct())
        .closedAt(p.getClosedAt())
        .build();
  }

  private String nullTo(String v, String d) {
    return v == null || v.isBlank() ? d : v;
  }

  private static class Agg {
    long count;
    long wins;
    BigDecimal sumPct = BigDecimal.ZERO;

    void add(BigDecimal pct, boolean win) {
      count++;
      if (win) wins++;
      sumPct = sumPct.add(pct == null ? BigDecimal.ZERO : pct);
    }
  }
}
