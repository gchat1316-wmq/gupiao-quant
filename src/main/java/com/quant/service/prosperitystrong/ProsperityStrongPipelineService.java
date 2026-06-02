package com.quant.service.prosperitystrong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.config.ProsperityStrongProperties;
import com.quant.dto.prosperitystrong.HotSectorDTO;
import com.quant.dto.prosperitystrong.LeaderCandidateDTO;
import com.quant.dto.prosperitystrong.PickDailyDTO;
import com.quant.dto.prosperitystrong.PipelineRunResultDTO;
import com.quant.entity.ProsperityHotSector;
import com.quant.entity.ProsperityLeaderCandidate;
import com.quant.entity.ProsperityPickDaily;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.ProsperityHotSectorRepository;
import com.quant.repository.ProsperityLeaderCandidateRepository;
import com.quant.repository.ProsperityPickDailyRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProsperityStrongPipelineService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProsperityStrongProperties props;
    private final HotSectorScanner sectorScanner;
    private final LeaderIdentifier leaderIdentifier;
    private final FinancialHardFilter financialFilter;
    private final MainlineEvaluator mainlineEvaluator;
    private final PositionAdvisor positionAdvisor;
    private final SectorNarrativeService narrativeService;

    private final ProsperityHotSectorRepository sectorRepo;
    private final ProsperityLeaderCandidateRepository leaderRepo;
    private final ProsperityPickDailyRepository pickRepo;
    private final TradeStockBasicRepository basicRepo;
    private final TradeStockDailyRepository dailyRepo;

    /** 全量执行四步流水线。 */
    @Transactional
    public PipelineRunResultDTO run(LocalDate snapDate) {
        LocalDateTime t0 = LocalDateTime.now();

        // 幂等: 清空当日数据(覆盖式)
        leaderRepo.deleteBySnapDate(snapDate);
        pickRepo.deleteBySnapDate(snapDate);
        sectorRepo.deleteBySnapDate(snapDate);

        // ===== Step 1 =====
        List<ProsperityHotSector> sectors = sectorScanner.scan(snapDate);
        sectors = sectorRepo.saveAll(sectors);
        for (ProsperityHotSector s : sectors) {
            try {
                String narrative = narrativeService.generate(s);
                s.setAiNarrative(narrative);
            } catch (Exception e) {
                log.warn("板块叙事失败: {} - {}", s.getSectorName(), e.getMessage());
            }
        }
        sectors = sectorRepo.saveAll(sectors);

        // ===== Step 2 =====
        List<ProsperityLeaderCandidate> allLeaders = new ArrayList<>();
        for (ProsperityHotSector s : sectors) {
            allLeaders.addAll(leaderIdentifier.identify(snapDate, s));
        }
        if (!allLeaders.isEmpty()) {
            allLeaders = leaderRepo.saveAll(allLeaders);
        }

        // ===== Step 3 + Step 4 + Step 5 =====
        List<ProsperityPickDaily> picks = new ArrayList<>();
        int hardPassedCount = 0;
        Map<String, TradeStockBasic> basicMap = new HashMap<>();
        for (TradeStockBasic b : basicRepo.findByStockCodeIn(
                allLeaders.stream().map(ProsperityLeaderCandidate::getStockCode).distinct().toList())) {
            basicMap.put(b.getStockCode(), b);
        }
        Map<String, TradeStockDaily> dailyMap = new HashMap<>();
        for (TradeStockDaily d : dailyRepo.findLatestByStockCodes(
                allLeaders.stream().map(ProsperityLeaderCandidate::getStockCode).distinct().toList())) {
            dailyMap.put(d.getStockCode(), d);
        }

        for (ProsperityLeaderCandidate c : allLeaders) {
            FinancialHardFilter.Result fin = financialFilter.evaluate(c.getStockCode());
            if (!fin.hardPassed()) {
                log.debug("[{}] 财务硬筛未通过: {}", c.getStockCode(), fin.reason());
                continue;
            }
            hardPassedCount++;

            // 主营占比目前无可靠数据源,MVP 阶段用默认值,Phase 2 接入年报披露
            MainlineEvaluator.Score mainline = mainlineEvaluator.evaluate(
                    null, fin.netMarginAvg4q(), fin.financeScore());

            if (!mainline.mainlinePassed() && mainline.netMarginAvg().doubleValue() < 10) {
                continue;
            }

            ProsperityPickDaily pick = new ProsperityPickDaily();
            pick.setSnapDate(snapDate);
            pick.setStockCode(c.getStockCode());
            pick.setStockName(c.getStockName());
            pick.setSectorName(c.getSectorName());
            pick.setFinanceScore(fin.financeScore());
            pick.setMainlineScore(mainline.mainlineScore());
            pick.setNetMarginAvg4q(fin.netMarginAvg4q());
            pick.setMainBizRatio(mainline.mainBizRatio());

            BigDecimal combined = combinedScore(
                    fin.financeScore(), mainline.mainlineScore(), c.getLeaderScore());
            pick.setCombinedScore(combined);

            TradeStockDaily d = dailyMap.get(c.getStockCode());
            BigDecimal latestPrice = d == null ? null : d.getClosePrice();
            pick.setLatestPrice(latestPrice);
            TradeStockBasic basic = basicMap.get(c.getStockCode());
            positionAdvisor.advise(pick, latestPrice,
                    basic == null ? null : basic.getPeTtm());

            pick.setDegraded(0);
            picks.add(pick);
        }

        // 按综合评分截断
        picks.sort(Comparator.comparing(ProsperityPickDaily::getCombinedScore,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (picks.size() > props.getMaxCandidates()) {
            picks = new ArrayList<>(picks.subList(0, props.getMaxCandidates()));
        }
        if (!picks.isEmpty()) {
            pickRepo.saveAll(picks);
        }

        LocalDateTime t1 = LocalDateTime.now();
        return PipelineRunResultDTO.builder()
                .snapDate(snapDate)
                .startedAt(t0)
                .finishedAt(t1)
                .durationMs(java.time.Duration.between(t0, t1).toMillis())
                .sectorCount(sectors.size())
                .leaderCount(allLeaders.size())
                .hardFilteredCount(hardPassedCount)
                .candidateCount(picks.size())
                .status("SUCCESS")
                .message(String.format("板块 %d / 龙头 %d / 硬筛通过 %d / 最终候选 %d",
                        sectors.size(), allLeaders.size(), hardPassedCount, picks.size()))
                .build();
    }

    private BigDecimal combinedScore(BigDecimal financeScore, BigDecimal mainlineScore, BigDecimal leaderScore) {
        double f = financeScore == null ? 0 : financeScore.doubleValue();
        double m = mainlineScore == null ? 0 : mainlineScore.doubleValue();
        double l = leaderScore == null ? 0 : leaderScore.doubleValue();
        double total = 0.4 * f + 0.4 * m + 0.2 * l;
        return BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
    }

    // ===== 查询接口 =====

    @Transactional(readOnly = true)
    public List<HotSectorDTO> sectors(LocalDate date) {
        LocalDate d = resolveDate(date);
        return sectorRepo.findBySnapDateOrderByRankNoAsc(d).stream().map(this::toSectorDTO).toList();
    }

    @Transactional(readOnly = true)
    public List<PickDailyDTO> candidates(LocalDate date) {
        LocalDate d = resolveDate(date);
        return pickRepo.findBySnapDateOrderByCombinedScoreDesc(d).stream()
                .map(p -> toPickDTO(p, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public PickDailyDTO detail(String stockCode, LocalDate date) {
        LocalDate d = resolveDate(date);
        ProsperityPickDaily pick = pickRepo.findBySnapDateAndStockCode(d, stockCode)
                .orElseThrow(() -> new IllegalArgumentException("未找到该股票候选: " + stockCode));
        return toPickDTO(pick, true);
    }

    @Transactional(readOnly = true)
    public List<LeaderCandidateDTO> leadersByDate(LocalDate date) {
        LocalDate d = resolveDate(date);
        return leaderRepo.findBySnapDateOrderByLeaderScoreDesc(d).stream()
                .map(this::toLeaderDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PickDailyDTO> history(LocalDate from, LocalDate to) {
        return pickRepo.findBySnapDateBetweenOrderBySnapDateDescCombinedScoreDesc(from, to).stream()
                .map(p -> toPickDTO(p, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public LocalDate latestSnapDate() {
        return pickRepo.findFirstByOrderBySnapDateDesc()
                .map(ProsperityPickDaily::getSnapDate)
                .or(() -> sectorRepo.findFirstByOrderBySnapDateDesc()
                        .map(ProsperityHotSector::getSnapDate))
                .orElse(null);
    }

    private LocalDate resolveDate(LocalDate date) {
        if (date != null) return date;
        LocalDate latest = latestSnapDate();
        return latest != null ? latest : LocalDate.now();
    }

    private HotSectorDTO toSectorDTO(ProsperityHotSector e) {
        return HotSectorDTO.builder()
                .id(e.getId()).snapDate(e.getSnapDate())
                .sectorCode(e.getSectorCode()).sectorName(e.getSectorName())
                .rankNo(e.getRankNo()).change1d(e.getChange1d())
                .change5d(e.getChange5d()).change20d(e.getChange20d())
                .capitalInflow5d(e.getCapitalInflow5d())
                .persistenceDays(e.getPersistenceDays())
                .score(e.getScore()).aiNarrative(e.getAiNarrative())
                .dataSource(e.getDataSource())
                .build();
    }

    private LeaderCandidateDTO toLeaderDTO(ProsperityLeaderCandidate e) {
        return LeaderCandidateDTO.builder()
                .id(e.getId()).snapDate(e.getSnapDate())
                .sectorId(e.getSectorId()).sectorName(e.getSectorName())
                .stockCode(e.getStockCode()).stockName(e.getStockName())
                .leaderScore(e.getLeaderScore()).ytdChange(e.getYtdChange())
                .change5d(e.getChange5d()).turnoverRate(e.getTurnoverRate())
                .mainInflow5d(e.getMainInflow5d())
                .filterPassed(e.getFilterPassed() != null && e.getFilterPassed() == 1)
                .filterReason(e.getFilterReason())
                .build();
    }

    private PickDailyDTO toPickDTO(ProsperityPickDaily e, boolean includeReport) {
        JsonNode report = null;
        if (includeReport && e.getAiReportJson() != null && !e.getAiReportJson().isBlank()) {
            try {
                report = MAPPER.readTree(e.getAiReportJson());
            } catch (Exception ignored) {}
        }
        return PickDailyDTO.builder()
                .id(e.getId()).snapDate(e.getSnapDate())
                .stockCode(e.getStockCode()).stockName(e.getStockName())
                .sectorName(e.getSectorName())
                .financeScore(e.getFinanceScore()).mainlineScore(e.getMainlineScore())
                .combinedScore(e.getCombinedScore())
                .netMarginAvg4q(e.getNetMarginAvg4q()).mainBizRatio(e.getMainBizRatio())
                .latestPrice(e.getLatestPrice())
                .priceLow(e.getPriceLow()).priceMid(e.getPriceMid()).priceHigh(e.getPriceHigh())
                .buyLeftPrice(e.getBuyLeftPrice()).buyRightPrice(e.getBuyRightPrice())
                .sellTarget1(e.getSellTarget1()).sellTarget2(e.getSellTarget2())
                .stopLossPrice(e.getStopLossPrice())
                .corePositionPct(e.getCorePositionPct()).tacticalPositionPct(e.getTacticalPositionPct())
                .actionSignal(e.getActionSignal())
                .aiReport(report)
                .degraded(e.getDegraded() != null && e.getDegraded() == 1)
                .createdAt(e.getCreatedAt())
                .build();
    }
}
