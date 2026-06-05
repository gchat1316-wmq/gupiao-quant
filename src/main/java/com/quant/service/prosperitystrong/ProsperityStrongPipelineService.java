package com.quant.service.prosperitystrong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.ProsperityHotSectorRepository;
import com.quant.repository.ProsperityLeaderCandidateRepository;
import com.quant.repository.ProsperityPickDailyRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.repository.TradeStockFinancialRepository;
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
import java.util.Objects;

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
    private final ProsperityDataProviderService providerService;

    private final ProsperityHotSectorRepository sectorRepo;
    private final ProsperityLeaderCandidateRepository leaderRepo;
    private final ProsperityPickDailyRepository pickRepo;
    private final TradeStockBasicRepository basicRepo;
    private final TradeStockDailyRepository dailyRepo;
    private final TradeStockFinancialRepository financialRepo;

    /** 全量执行四步流水线。 */
    @Transactional
    public PipelineRunResultDTO run(LocalDate snapDate) {
        return run(snapDate, null);
    }

    /** 全量执行四步流水线。 */
    @Transactional
    public PipelineRunResultDTO run(LocalDate snapDate, String provider) {
        LocalDateTime t0 = LocalDateTime.now();
        String selectedProvider = providerService.normalize(provider);
        String providerMessage = providerService.providerMessage(selectedProvider);
        log.info("强势股流水线开始: date={}, provider={}, providerMessage={}",
                snapDate, selectedProvider, providerMessage);

        // 幂等: 清空当日数据(覆盖式)
        leaderRepo.deleteBySnapDate(snapDate);
        pickRepo.deleteBySnapDate(snapDate);
        sectorRepo.deleteBySnapDate(snapDate);
        leaderRepo.flush();
        pickRepo.flush();
        sectorRepo.flush();

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
            // Step2 快速过滤
            if (c.getFilterPassed() == null || c.getFilterPassed() != 1) {
                c.setFinalStage("leader_filter");
                continue;
            }

            // Step3 财务硬筛
            FinancialHardFilter.Result fin = financialFilter.evaluate(c.getStockCode());
            c.setFinanceScore(fin.financeScore());
            c.setFinancePassed(fin.hardPassed() ? 1 : 0);
            c.setFinanceReason(fin.reason());
            if (!fin.hardPassed()) {
                c.setFinalStage("finance_filter");
                continue;
            }

            // Step4 主线判定
            MainlineEvaluator.Score mainline = mainlineEvaluator.evaluate(
                    null, fin.netMarginAvg4q(), fin.financeScore());
            c.setMainlineScore(mainline.mainlineScore());
            c.setMainlinePassed(mainline.mainlinePassed() ? 1 : 0);
            if (!mainline.mainlinePassed() && mainline.netMarginAvg().doubleValue() < 10) {
                c.setFinalStage("mainline_filter");
                continue;
            }

            c.setFinalStage("passed");
            hardPassedCount++;

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

        // 保存全部候选(含被过滤的,已标注 finalStage)
        if (!allLeaders.isEmpty()) {
            leaderRepo.saveAll(allLeaders);
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
                .provider(selectedProvider)
                .providerMessage(providerMessage)
                .status("SUCCESS")
                .message(String.format("[%s] 板块 %d / 龙头 %d / 硬筛通过 %d / 最终候选 %d",
                        selectedProvider, sectors.size(), allLeaders.size(), hardPassedCount, picks.size()))
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
        List<ProsperityHotSector> sectorList = sectorRepo.findBySnapDateOrderByRankNoAsc(d);
        List<ProsperityLeaderCandidate> allLeaders = leaderRepo.findBySnapDateOrderByLeaderScoreDesc(d);
        Map<Integer, List<ProsperityLeaderCandidate>> bySectorId = allLeaders.stream()
                .collect(java.util.stream.Collectors.groupingBy(ProsperityLeaderCandidate::getSectorId));
        return sectorList.stream().map(s -> {
            List<LeaderCandidateDTO> leaders = bySectorId.getOrDefault(s.getId(), List.of()).stream()
                    .map(this::toLeaderDTO).toList();
            LeaderIdentifier.MemberStats stats = leaderIdentifier.memberStats(s.getSectorName());
            return toSectorDTO(s, leaders, stats);
        }).toList();
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

    private HotSectorDTO toSectorDTO(ProsperityHotSector e, List<LeaderCandidateDTO> leaders,
                                     LeaderIdentifier.MemberStats stats) {
        return HotSectorDTO.builder()
                .id(e.getId()).snapDate(e.getSnapDate())
                .sectorCode(e.getSectorCode()).sectorName(e.getSectorName())
                .rankNo(e.getRankNo()).change1d(e.getChange1d())
                .change5d(e.getChange5d()).change20d(e.getChange20d())
                .capitalInflow5d(e.getCapitalInflow5d())
                .persistenceDays(e.getPersistenceDays())
                .score(e.getScore()).aiNarrative(e.getAiNarrative())
                .dataSource(e.getDataSource())
                .leaders(leaders)
                .matchedMemberCount(stats.matchedMemberCount())
                .quotedMemberCount(stats.quotedMemberCount())
                .diagnosticMessage(stats.diagnosticMessage())
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
                .financeScore(e.getFinanceScore())
                .financePassed(e.getFinancePassed() != null && e.getFinancePassed() == 1)
                .financeReason(e.getFinanceReason())
                .mainlineScore(e.getMainlineScore())
                .mainlinePassed(e.getMainlinePassed() != null && e.getMainlinePassed() == 1)
                .finalStage(e.getFinalStage())
                .build();
    }

    private PickDailyDTO toPickDTO(ProsperityPickDaily e, boolean includeReport) {
        JsonNode report = null;
        List<PickDailyDTO.ProfitQuarterDTO> profitQuarters = includeReport ? profitQuarters(e.getStockCode()) : List.of();
        if (includeReport) {
            if (e.getAiReportJson() != null && !e.getAiReportJson().isBlank()) {
                try {
                    report = MAPPER.readTree(e.getAiReportJson());
                } catch (Exception ignored) {}
            }
            if (report == null) {
                report = generatedReport(e, profitQuarters);
            }
        }
        return PickDailyDTO.builder()
                .id(e.getId()).snapDate(e.getSnapDate())
                .stockCode(e.getStockCode()).stockName(e.getStockName())
                .sectorName(e.getSectorName())
                .financeScore(e.getFinanceScore()).mainlineScore(e.getMainlineScore())
                .combinedScore(e.getCombinedScore())
                .netMarginAvg4q(e.getNetMarginAvg4q()).mainBizRatio(e.getMainBizRatio())
                .latestPrice(e.getLatestPrice())
                .profitQuarters(profitQuarters)
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

    private JsonNode generatedReport(ProsperityPickDaily e, List<PickDailyDTO.ProfitQuarterDTO> profitQuarters) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("source", "system_generated");
        root.put("title", e.getStockName() + " 强势股深度报告");
        root.put("summary", String.format("%s 属于%s板块，综合分%s，当前信号为%s。",
                e.getStockName(), safe(e.getSectorName()), safe(e.getCombinedScore()), signalText(e.getActionSignal())));
        ArrayNode sections = root.putArray("sections");
        addSection(sections, "结论", List.of(
                "当前纳入最终候选，说明已通过龙头筛选、财务硬筛和主线判定。",
                "操作信号为「" + signalText(e.getActionSignal()) + "」，需要结合价格所处区间执行。"
        ));
        addSection(sections, "主线与强度", List.of(
                "所属板块：" + safe(e.getSectorName()),
                "综合评分：" + safe(e.getCombinedScore()) + "，主线评分：" + safe(e.getMainlineScore()),
                "当前价格：" + money(e.getLatestPrice())
        ));
        addSection(sections, "财务质量", List.of(
                "财务评分：" + safe(e.getFinanceScore()),
                "近4季平均净利率：" + pct(e.getNetMarginAvg4q()),
                profitSummary(profitQuarters)
        ));
        addSection(sections, "价格路径", List.of(
                "左侧建仓价：" + money(e.getBuyLeftPrice()) + "，右侧确认价：" + money(e.getBuyRightPrice()),
                "第一目标价：" + money(e.getSellTarget1()) + "，第二目标价：" + money(e.getSellTarget2()),
                "止损价：" + money(e.getStopLossPrice()) + "，保守/中性/乐观估值分别为 "
                        + money(e.getPriceLow()) + " / " + money(e.getPriceMid()) + " / " + money(e.getPriceHigh())
        ));
        addSection(sections, "仓位建议", List.of(
                "核心仓位：" + pct(e.getCorePositionPct()) + "，战术仓位：" + pct(e.getTacticalPositionPct()),
                "单股最大仓位不超过10%，单板块最大仓位不超过30%，总仓位不超过80%。"
        ));
        addSection(sections, "风险与跟踪", List.of(
                "若跌破止损价或板块主线热度明显降温，应降低仓位或退出观察。",
                "重点跟踪后续季度利润是否延续增长，以及价格是否重新回到建仓/确认区间。"
        ));
        return root;
    }

    private void addSection(ArrayNode sections, String title, List<String> points) {
        ObjectNode section = sections.addObject();
        section.put("title", title);
        ArrayNode arr = section.putArray("points");
        for (String p : points) arr.add(p);
    }

    private String profitSummary(List<PickDailyDTO.ProfitQuarterDTO> quarters) {
        if (quarters == null || quarters.isEmpty()) return "近4季单季利润数据不足。";
        PickDailyDTO.ProfitQuarterDTO first = quarters.get(0);
        PickDailyDTO.ProfitQuarterDTO last = quarters.get(quarters.size() - 1);
        String trend = "";
        if (first.getNetProfit() != null && last.getNetProfit() != null
                && first.getNetProfit().compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal change = last.getNetProfit().subtract(first.getNetProfit())
                    .divide(first.getNetProfit().abs(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
            trend = "，区间变化 " + pct(change);
        }
        return "最近季度 " + last.getLabel() + " 单季净利润 " + money(last.getNetProfit())
                + "，环比 " + pct(last.getQoqPct()) + trend + "。";
    }

    private String signalText(String signal) {
        if ("add".equals(signal)) return "加仓";
        if ("hold".equals(signal)) return "持有";
        if ("reduce".equals(signal)) return "减仓";
        if ("observe".equals(signal)) return "观察";
        return "--";
    }

    private String safe(Object value) {
        return value == null ? "--" : value.toString();
    }

    private String money(BigDecimal value) {
        return value == null ? "--" : "¥" + value.setScale(2, RoundingMode.HALF_UP);
    }

    private String pct(BigDecimal value) {
        return value == null ? "--" : value.setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private List<PickDailyDTO.ProfitQuarterDTO> profitQuarters(String stockCode) {
        List<TradeStockFinancial> records = financialRepo.findByStockCodeOrderByReportDateDesc(stockCode).stream()
                .filter(f -> f.getReportDate() != null && f.getNetProfit() != null)
                .sorted(Comparator.comparing(TradeStockFinancial::getReportDate))
                .toList();
        if (records.isEmpty()) return List.of();

        Map<java.time.LocalDate, TradeStockFinancial> byDate = new HashMap<>();
        for (TradeStockFinancial f : records) {
            byDate.put(f.getReportDate(), f);
        }

        List<QuarterProfit> quarters = records.stream()
                .map(f -> toQuarterProfit(f, byDate))
                .filter(Objects::nonNull)
                .toList();
        if (quarters.isEmpty()) return List.of();

        int from = Math.max(0, quarters.size() - 4);
        return quarters.subList(from, quarters.size()).stream()
                .map(q -> PickDailyDTO.ProfitQuarterDTO.builder()
                        .reportDate(q.reportDate())
                        .label(q.label())
                        .netProfit(q.netProfit())
                        .qoqPct(q.qoqPct())
                        .netMargin(q.netMargin())
                        .build())
                .toList();
    }

    private QuarterProfit toQuarterProfit(TradeStockFinancial f, Map<java.time.LocalDate, TradeStockFinancial> byDate) {
        int month = f.getReportDate().getMonthValue();
        BigDecimal quarterProfit = f.getNetProfit();
        if (month == 6 || month == 9 || month == 12) {
            java.time.LocalDate prev = switch (month) {
                case 6 -> java.time.LocalDate.of(f.getReportDate().getYear(), 3, 31);
                case 9 -> java.time.LocalDate.of(f.getReportDate().getYear(), 6, 30);
                default -> java.time.LocalDate.of(f.getReportDate().getYear(), 9, 30);
            };
            TradeStockFinancial prevRecord = byDate.get(prev);
            if (prevRecord != null && prevRecord.getNetProfit() != null) {
                quarterProfit = f.getNetProfit().subtract(prevRecord.getNetProfit());
            }
        }
        BigDecimal qoq = null;
        java.time.LocalDate prevQuarter = previousQuarterEnd(f.getReportDate());
        TradeStockFinancial prevQuarterRecord = byDate.get(prevQuarter);
        if (prevQuarterRecord != null) {
            QuarterProfit prevQ = toQuarterProfitWithoutQoq(prevQuarterRecord, byDate);
            if (prevQ != null && prevQ.netProfit() != null
                    && prevQ.netProfit().compareTo(BigDecimal.ZERO) != 0) {
                qoq = quarterProfit.subtract(prevQ.netProfit())
                        .divide(prevQ.netProfit().abs(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);
            }
        }
        return new QuarterProfit(f.getReportDate(), quarterLabel(f.getReportDate()), quarterProfit, qoq, f.getNetMargin());
    }

    private QuarterProfit toQuarterProfitWithoutQoq(TradeStockFinancial f, Map<java.time.LocalDate, TradeStockFinancial> byDate) {
        int month = f.getReportDate().getMonthValue();
        BigDecimal quarterProfit = f.getNetProfit();
        if (month == 6 || month == 9 || month == 12) {
            java.time.LocalDate prev = switch (month) {
                case 6 -> java.time.LocalDate.of(f.getReportDate().getYear(), 3, 31);
                case 9 -> java.time.LocalDate.of(f.getReportDate().getYear(), 6, 30);
                default -> java.time.LocalDate.of(f.getReportDate().getYear(), 9, 30);
            };
            TradeStockFinancial prevRecord = byDate.get(prev);
            if (prevRecord != null && prevRecord.getNetProfit() != null) {
                quarterProfit = f.getNetProfit().subtract(prevRecord.getNetProfit());
            }
        }
        return new QuarterProfit(f.getReportDate(), quarterLabel(f.getReportDate()), quarterProfit, null, f.getNetMargin());
    }

    private java.time.LocalDate previousQuarterEnd(java.time.LocalDate date) {
        return switch (date.getMonthValue()) {
            case 3 -> java.time.LocalDate.of(date.getYear() - 1, 12, 31);
            case 6 -> java.time.LocalDate.of(date.getYear(), 3, 31);
            case 9 -> java.time.LocalDate.of(date.getYear(), 6, 30);
            default -> java.time.LocalDate.of(date.getYear(), 9, 30);
        };
    }

    private String quarterLabel(java.time.LocalDate date) {
        int q = switch (date.getMonthValue()) {
            case 3 -> 1;
            case 6 -> 2;
            case 9 -> 3;
            default -> 4;
        };
        return String.format("%dQ%d", date.getYear(), q);
    }

    private record QuarterProfit(java.time.LocalDate reportDate, String label,
                                 BigDecimal netProfit, BigDecimal qoqPct, BigDecimal netMargin) {}
}
