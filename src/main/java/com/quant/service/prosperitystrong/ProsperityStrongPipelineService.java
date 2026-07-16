package com.quant.service.prosperitystrong;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.quant.config.ProsperityStrongProperties;
import com.quant.dto.prosperitystrong.HotSectorDTO;
import com.quant.dto.prosperitystrong.LeaderCandidateDTO;
import com.quant.dto.prosperitystrong.PickDailyDTO;
import com.quant.dto.prosperitystrong.PipelineRunDTO;
import com.quant.dto.prosperitystrong.PipelineRunResultDTO;
import com.quant.entity.ProsperityHotSector;
import com.quant.entity.ProsperityLeaderCandidate;
import com.quant.entity.ProsperityPickDaily;
import com.quant.entity.ProsperityPipelineRun;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.ProsperityHotSectorRepository;
import com.quant.repository.ProsperityLeaderCandidateRepository;
import com.quant.repository.ProsperityPickDailyRepository;
import com.quant.repository.ProsperityPipelineRunRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProsperityStrongPipelineService {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * 同进程内流水线互斥,避免定时器 + 手动触发并发跑同一份快照。 历史教训: 并发 insert prosperity_hot_sector 会触发
   * innodb_lock_wait_timeout (50s), 前端流水线程会报 HTTP 500。
   */
  private final ReentrantLock runLock = new ReentrantLock(true);

  private final ProsperityStrongProperties props;
  private final HotSectorScanner sectorScanner;
  private final LeaderIdentifier leaderIdentifier;
  private final FinancialHardFilter financialFilter;
  private final MainlineEvaluator mainlineEvaluator;
  private final PositionAdvisor positionAdvisor;
  private final SectorNarrativeService narrativeService;
  private final ProsperityDataProviderService providerService;
  private final ProsperityStrongCleanupService cleanupService;

  private final ProsperityHotSectorRepository sectorRepo;
  private final ProsperityLeaderCandidateRepository leaderRepo;
  private final ProsperityPickDailyRepository pickRepo;
  private final ProsperityPipelineRunRepository runRepo;
  private final TradeStockBasicRepository basicRepo;
  private final TradeStockFinancialRepository financialRepo;
  private final AStockDataQuoteService aStockDataQuoteService;

  /** 全量执行四步流水线。 */
  public PipelineRunResultDTO run(LocalDate snapDate) {
    return run(snapDate, null);
  }

  /**
   * 全量执行四步流水线。
   *
   * <p>并发策略:
   *
   * <ol>
   *   <li>同进程内 {@link #runLock} 互斥, 拒绝并发触发 (前端/定时器只允许一条在跑)
   *   <li>delete 走 {@link ProsperityStrongCleanupService} 的 REQUIRES_NEW 短事务, 立刻释放 uk_date_sector
   *       唯一键的行锁, 避免 insert 阶段锁等待
   *   <li>主体 Step1~5 在本事务内一次提交, 缩小锁持有窗口
   * </ol>
   */
  @Transactional
  public PipelineRunResultDTO run(LocalDate snapDate, String provider) {
    LocalDateTime t0 = LocalDateTime.now();
    boolean acquired;
    try {
      acquired = runLock.tryLock(0, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      PipelineRunResultDTO result =
          PipelineRunResultDTO.builder()
              .snapDate(snapDate)
              .startedAt(t0)
              .finishedAt(LocalDateTime.now())
              .durationMs(0L)
              .provider(provider == null ? "" : provider)
              .status("INTERRUPTED")
              .message("流水线被中断")
              .build();
      savePipelineRun(snapDate, result);
      return result;
    }
    if (!acquired) {
      log.warn("热点选股流水线正在执行中,本次请求被拒绝: snapDate={}, provider={}", snapDate, provider);
      PipelineRunResultDTO result =
          PipelineRunResultDTO.builder()
              .snapDate(snapDate)
              .startedAt(t0)
              .finishedAt(LocalDateTime.now())
              .durationMs(0L)
              .provider(provider == null ? "" : provider)
              .status("BUSY")
              .message("流水线正在执行中,请稍后再试")
              .build();
      savePipelineRun(snapDate, result);
      return result;
    }
    try {
      String selectedProvider = providerService.normalize(provider);
      String providerMessage = providerService.providerMessage(selectedProvider);
      log.info(
          "热点选股流水线开始: date={}, provider={}, providerMessage={}",
          snapDate,
          selectedProvider,
          providerMessage);

      // 幂等: 清空当日数据 (独立短事务, 立刻释放行锁)
      cleanupService.clearSnapDate(snapDate);

      PipelineRunResultDTO result = runPipeline(snapDate, selectedProvider, providerMessage, t0);

      // 记录本次执行（幂等: 同一 snapDate 只保留最新一条）
      savePipelineRun(snapDate, result);

      return result;
    } finally {
      runLock.unlock();
    }
  }

  /** 保存流水线执行记录，幂等（同一日期只保留最新一条）。 */
  private void savePipelineRun(LocalDate snapDate, PipelineRunResultDTO result) {
    ProsperityPipelineRun run =
        runRepo.findTopBySnapDateOrderByStartedAtDesc(snapDate).orElse(new ProsperityPipelineRun());
    run.setSnapDate(snapDate);
    run.setStartedAt(result.getStartedAt());
    run.setFinishedAt(result.getFinishedAt());
    run.setDurationMs(result.getDurationMs());
    run.setStatus(result.getStatus());
    run.setMessage(result.getMessage());
    run.setProvider(result.getProvider());
    run.setSectorCount(result.getSectorCount());
    run.setLeaderCount(result.getLeaderCount());
    run.setHardFilteredCount(result.getHardFilteredCount());
    run.setCandidateCount(result.getCandidateCount());
    runRepo.save(run);
  }

  /** 流水线主体 Step1~5, 与 {@link #run} 同事务。 */
  private PipelineRunResultDTO runPipeline(
      LocalDate snapDate, String selectedProvider, String providerMessage, LocalDateTime t0) {

    // ===== Step 1 =====
    List<ProsperityHotSector> sectors = sectorScanner.scan(snapDate, selectedProvider);
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
    for (TradeStockBasic b :
        basicRepo.findByStockCodeIn(
            allLeaders.stream().map(ProsperityLeaderCandidate::getStockCode).distinct().toList())) {
      basicMap.put(b.getStockCode(), b);
    }
    // 当前股价统一走 a-stock-data 实时接口；trade_stock_daily 收盘价同步延迟、不准确
    Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap =
        aStockDataQuoteService.fetchQuotes(
            allLeaders.stream().map(ProsperityLeaderCandidate::getStockCode).distinct().toList());

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
      c.setRevenueYoyMin4q(fin.revenueYoyMin3q());
      c.setDeductedNetProfitYoyMin4q(fin.deductedNetProfitYoyMin3q());
      if (!fin.hardPassed()) {
        c.setFinalStage("finance_filter");
        // 财务筛不通过时仍跑一遍主线评估,把 mainlinePassed/mainlineReason
        // 落库,供"成分股过滤明细"里给用户看全每只股票三个阶段的原因。
        MainlineEvaluator.Score mlForRecord =
            mainlineEvaluator.evaluate(null, null, fin.financeScore());
        c.setMainlineScore(mlForRecord.mainlineScore());
        c.setMainlinePassed(mlForRecord.mainlinePassed() ? 1 : 0);
        c.setMainlineReason(mlForRecord.mainlineReason());
        continue;
      }

      // Step4 主线判定
      MainlineEvaluator.Score mainline = mainlineEvaluator.evaluate(null, null, fin.financeScore());
      c.setMainlineScore(mainline.mainlineScore());
      c.setMainlinePassed(mainline.mainlinePassed() ? 1 : 0);
      c.setMainlineReason(mainline.mainlineReason());
      if (!mainline.mainlinePassed()) {
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
      pick.setRevenueYoyMin3q(fin.revenueYoyMin3q());
      pick.setMainBizRatio(mainline.mainBizRatio());

      BigDecimal combined =
          combinedScore(fin.financeScore(), mainline.mainlineScore(), c.getLeaderScore());
      pick.setCombinedScore(combined);

      AStockDataQuoteService.QuoteSnapshot snapshot =
          quoteMap.get(
              c.getStockCode() == null ? "" : c.getStockCode().trim().toUpperCase(Locale.ROOT));
      BigDecimal latestPrice = snapshot == null ? null : snapshot.latestPrice();
      pick.setLatestPrice(latestPrice);
      TradeStockBasic basic = basicMap.get(c.getStockCode());
      positionAdvisor.advise(pick, latestPrice, basic == null ? null : basic.getPeTtm());

      pick.setDegraded(0);
      picks.add(pick);
    }

    // 保存全部候选(含被过滤的,已标注 finalStage)
    if (!allLeaders.isEmpty()) {
      leaderRepo.saveAll(allLeaders);
    }

    // 按综合评分截断
    picks.sort(
        Comparator.comparing(
            ProsperityPickDaily::getCombinedScore,
            Comparator.nullsLast(Comparator.reverseOrder())));

    // 去重: 同一只股票在多个板块(eg AI算力+工业母机)出现时,
    // prosperity_pick_daily.uk_date_code(snap_date, stock_code) 唯一键冲突。
    // 保留综合分最高的那条,并把"次优板块"信息塞进 memo 备注,避免信息丢失。
    picks = dedupPicks(picks);

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
        .message(
            String.format(
                "[%s] 板块 %d / 龙头 %d / 硬筛通过 %d / 最终候选 %d",
                selectedProvider, sectors.size(), allLeaders.size(), hardPassedCount, picks.size()))
        .build();
  }

  /** 去重 picks: 同 snapDate + stockCode 只保留综合分最高的一条。 其它板块的来源塞进 memo, 保证前端仍能看到这只股票关联了哪几个板块。 */
  List<ProsperityPickDaily> dedupPicks(List<ProsperityPickDaily> picks) {
    Map<String, ProsperityPickDaily> bestByCode = new LinkedHashMap<>();
    Map<String, List<String>> sectorsByCode = new HashMap<>();
    for (ProsperityPickDaily p : picks) {
      String code = p.getStockCode();
      if (code == null) continue;
      sectorsByCode.computeIfAbsent(code, k -> new ArrayList<>()).add(p.getSectorName());
      ProsperityPickDaily prev = bestByCode.get(code);
      if (prev == null) {
        bestByCode.put(code, p);
        continue;
      }
      BigDecimal prevScore =
          prev.getCombinedScore() == null ? BigDecimal.ZERO : prev.getCombinedScore();
      BigDecimal currScore = p.getCombinedScore() == null ? BigDecimal.ZERO : p.getCombinedScore();
      if (currScore.compareTo(prevScore) > 0) {
        bestByCode.put(code, p);
      }
    }
    for (ProsperityPickDaily p : bestByCode.values()) {
      List<String> sects = sectorsByCode.getOrDefault(p.getStockCode(), List.of());
      if (sects.size() > 1) {
        String other =
            sects.stream()
                .filter(s -> !s.equals(p.getSectorName()))
                .collect(Collectors.joining(", "));
        p.setMemo(
            (p.getMemo() == null ? "" : p.getMemo() + "\n")
                + "[板块归属] "
                + p.getSectorName()
                + " (另入选: "
                + other
                + ")");
      }
    }
    // 仍按综合分降序
    List<ProsperityPickDaily> result = new ArrayList<>(bestByCode.values());
    result.sort(
        Comparator.comparing(
            ProsperityPickDaily::getCombinedScore,
            Comparator.nullsLast(Comparator.reverseOrder())));
    return result;
  }

  private BigDecimal combinedScore(
      BigDecimal financeScore, BigDecimal mainlineScore, BigDecimal leaderScore) {
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
    // sectors 列表拉轻量投影（不含 aiNarrative TEXT），避免 InnoDB off-page 读
    List<SectorSummaryDTO> sectorList = sectorRepo.findSummaryBySnapDate(d);
    // aiNarrative 单独 batch 拉（只查 TEXT 字段）
    java.util.Map<Integer, String> narrativeMap =
        sectorList.isEmpty()
            ? java.util.Collections.emptyMap()
            : sectorRepo.findAiNarrativeBatch(
                sectorList.stream()
                    .map(SectorSummaryDTO::getId)
                    .collect(java.util.stream.Collectors.toList()));
    List<ProsperityLeaderCandidate> allLeaders = leaderRepo.findBySnapDateOrderByLeaderScoreDesc(d);
    Map<Integer, List<ProsperityLeaderCandidate>> bySectorId =
        allLeaders.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(ProsperityLeaderCandidate::getSectorId));
    return sectorList.stream()
        .map(
            s -> {
              List<LeaderCandidateDTO> leaders =
                  bySectorId.getOrDefault(s.getId(), List.of()).stream()
                      .map(this::toLeaderDTO)
                      .toList();
              LeaderIdentifier.MemberStats stats =
                  leaderIdentifier.memberStatsByName(s.getSectorName());
              return toSectorDTO(s, narrativeMap.get(s.getId()), leaders, stats);
            })
        .toList();
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
    ProsperityPickDaily pick =
        pickRepo
            .findBySnapDateAndStockCode(d, stockCode)
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

  /** 查询流水线执行历史 */
  @Transactional(readOnly = true)
  public List<PipelineRunDTO> runs(LocalDate from, LocalDate to) {
    return runRepo.findBySnapDateBetweenOrderByStartedAtDesc(from, to).stream()
        .map(this::toRunDTO)
        .toList();
  }

  /** 删除指定日期的所有数据（prosperity_hot_sector + leader_candidate + pick_daily + pipeline_run） */
  @Transactional
  public void deleteRun(LocalDate snapDate) {
    cleanupService.clearSnapDate(snapDate);
    runRepo.deleteBySnapDate(snapDate);
  }

  private PipelineRunDTO toRunDTO(ProsperityPipelineRun r) {
    return PipelineRunDTO.builder()
        .id(r.getId())
        .snapDate(r.getSnapDate())
        .startedAt(r.getStartedAt())
        .finishedAt(r.getFinishedAt())
        .durationMs(r.getDurationMs())
        .status(r.getStatus())
        .message(r.getMessage())
        .provider(r.getProvider())
        .sectorCount(r.getSectorCount())
        .leaderCount(r.getLeaderCount())
        .hardFilteredCount(r.getHardFilteredCount())
        .candidateCount(r.getCandidateCount())
        .build();
  }

  @Transactional(readOnly = true)
  public LocalDate latestSnapDate() {
    return pickRepo
        .findFirstByOrderBySnapDateDesc()
        .map(ProsperityPickDaily::getSnapDate)
        .or(() -> sectorRepo.findFirstByOrderBySnapDateDesc().map(ProsperityHotSector::getSnapDate))
        .orElse(null);
  }

  private LocalDate resolveDate(LocalDate date) {
    if (date != null) return date;
    LocalDate latest = latestSnapDate();
    return latest != null ? latest : LocalDate.now();
  }

  private HotSectorDTO toSectorDTO(
      SectorSummaryDTO s,
      String aiNarrative,
      List<LeaderCandidateDTO> leaders,
      LeaderIdentifier.MemberStats stats) {
    return HotSectorDTO.builder()
        .id(s.getId())
        .snapDate(s.getSnapDate())
        .sectorCode(s.getSectorCode())
        .sectorName(s.getSectorName())
        .rankNo(s.getRankNo())
        .change1d(s.getChange1d())
        .change5d(s.getChange5d())
        .change20d(s.getChange20d())
        .capitalInflow5d(s.getCapitalInflow5d())
        .upCount(s.getUpCount())
        .downCount(s.getDownCount())
        .leadStock(s.getLeadStock())
        .leadStockChange(s.getLeadStockChange())
        .persistenceDays(s.getPersistenceDays())
        .score(s.getScore())
        .aiNarrative(aiNarrative)
        .dataSource(s.getDataSource())
        .leaders(leaders)
        .matchedMemberCount(stats.matchedMemberCount())
        .quotedMemberCount(stats.quotedMemberCount())
        .diagnosticMessage(stats.diagnosticMessage())
        .build();
  }

  /** 保留原 Entity 重载供其他路径（如 sectorsByRank 等）使用。 */
  private HotSectorDTO toSectorDTO(
      ProsperityHotSector e, List<LeaderCandidateDTO> leaders, LeaderIdentifier.MemberStats stats) {
    return toSectorDTO(
        new SectorSummaryDTO(
            e.getId(),
            e.getSnapDate(),
            e.getSectorCode(),
            e.getSectorName(),
            e.getRankNo(),
            e.getChange1d(),
            e.getChange5d(),
            e.getChange20d(),
            e.getCapitalInflow5d(),
            e.getUpCount(),
            e.getDownCount(),
            e.getLeadStock(),
            e.getLeadStockChange(),
            e.getPersistenceDays(),
            e.getScore(),
            e.getDataSource()),
        e.getAiNarrative(),
        leaders,
        stats);
  }

  private LeaderCandidateDTO toLeaderDTO(ProsperityLeaderCandidate e) {
    return LeaderCandidateDTO.builder()
        .id(e.getId())
        .snapDate(e.getSnapDate())
        .sectorId(e.getSectorId())
        .sectorName(e.getSectorName())
        .stockCode(e.getStockCode())
        .stockName(e.getStockName())
        .leaderScore(e.getLeaderScore())
        .ytdChange(e.getYtdChange())
        .change5d(e.getChange5d())
        .turnoverRate(e.getTurnoverRate())
        .mainInflow5d(e.getMainInflow5d())
        .filterPassed(e.getFilterPassed() != null && e.getFilterPassed() == 1)
        .filterReason(e.getFilterReason())
        .financeScore(e.getFinanceScore())
        .financePassed(e.getFinancePassed() != null && e.getFinancePassed() == 1)
        .financeReason(e.getFinanceReason())
        .mainlineScore(e.getMainlineScore())
        .mainlinePassed(e.getMainlinePassed() != null && e.getMainlinePassed() == 1)
        .mainlineReason(e.getMainlineReason())
        .finalStage(e.getFinalStage())
        .revenueYoyMin4q(e.getRevenueYoyMin4q())
        .deductedNetProfitYoyMin4q(e.getDeductedNetProfitYoyMin4q())
        .grossMarginAvg4q(e.getGrossMarginAvg4q())
        .debtRatioLatest(e.getDebtRatioLatest())
        .operatingCashflowSum4q(e.getOperatingCashflowSum4q())
        .roeLatest(e.getRoeLatest())
        .build();
  }

  private PickDailyDTO toPickDTO(ProsperityPickDaily e, boolean includeReport) {
    JsonNode report = null;
    List<PickDailyDTO.ProfitQuarterDTO> profitQuarters =
        includeReport ? profitQuarters(e.getStockCode()) : List.of();
    if (includeReport) {
      if (e.getAiReportJson() != null && !e.getAiReportJson().isBlank()) {
        try {
          report = MAPPER.readTree(e.getAiReportJson());
        } catch (Exception ignored) {
        }
      }
      if (report == null) {
        report = generatedReport(e, profitQuarters);
      }
    }
    return PickDailyDTO.builder()
        .id(e.getId())
        .snapDate(e.getSnapDate())
        .stockCode(e.getStockCode())
        .stockName(e.getStockName())
        .sectorName(e.getSectorName())
        .financeScore(e.getFinanceScore())
        .mainlineScore(e.getMainlineScore())
        .combinedScore(e.getCombinedScore())
        .netMarginAvg4q(null)
        .revenueYoyMin3q(e.getRevenueYoyMin3q())
        .mainBizRatio(e.getMainBizRatio())
        .latestPrice(e.getLatestPrice())
        .profitQuarters(profitQuarters)
        .priceLow(e.getPriceLow())
        .priceMid(e.getPriceMid())
        .priceHigh(e.getPriceHigh())
        .buyLeftPrice(e.getBuyLeftPrice())
        .buyRightPrice(e.getBuyRightPrice())
        .sellTarget1(e.getSellTarget1())
        .sellTarget2(e.getSellTarget2())
        .stopLossPrice(e.getStopLossPrice())
        .corePositionPct(e.getCorePositionPct())
        .tacticalPositionPct(e.getTacticalPositionPct())
        .actionSignal(e.getActionSignal())
        .aiReport(report)
        .degraded(e.getDegraded() != null && e.getDegraded() == 1)
        .createdAt(e.getCreatedAt())
        .build();
  }

  private JsonNode generatedReport(
      ProsperityPickDaily e, List<PickDailyDTO.ProfitQuarterDTO> profitQuarters) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("source", "system_generated");
    root.put("title", e.getStockName() + " 强势股深度报告");
    root.put(
        "summary",
        String.format(
            "%s 属于%s板块，综合分%s，当前信号为%s。",
            e.getStockName(),
            safe(e.getSectorName()),
            safe(e.getCombinedScore()),
            signalText(e.getActionSignal())));
    ArrayNode sections = root.putArray("sections");
    addSection(
        sections,
        "结论",
        List.of(
            "当前纳入最终候选，说明已通过龙头筛选、财务硬筛和主线判定。",
            "操作信号为「" + signalText(e.getActionSignal()) + "」，需要结合价格所处区间执行。"));
    addSection(
        sections,
        "主线与强度",
        List.of(
            "所属板块：" + safe(e.getSectorName()),
            "综合评分：" + safe(e.getCombinedScore()) + "，主线评分：" + safe(e.getMainlineScore()),
            "当前价格：" + money(e.getLatestPrice())));
    addSection(
        sections,
        "财务质量",
        List.of(
            "财务评分：" + safe(e.getFinanceScore()),
            "近4季平均净利率：" + pct(e.getNetMarginAvg4q()),
            profitSummary(profitQuarters)));
    addSection(
        sections,
        "价格路径",
        List.of(
            "左侧建仓价：" + money(e.getBuyLeftPrice()) + "，右侧确认价：" + money(e.getBuyRightPrice()),
            "第一目标价：" + money(e.getSellTarget1()) + "，第二目标价：" + money(e.getSellTarget2()),
            "止损价："
                + money(e.getStopLossPrice())
                + "，保守/中性/乐观估值分别为 "
                + money(e.getPriceLow())
                + " / "
                + money(e.getPriceMid())
                + " / "
                + money(e.getPriceHigh())));
    addSection(
        sections,
        "仓位建议",
        List.of(
            "核心仓位：" + pct(e.getCorePositionPct()) + "，战术仓位：" + pct(e.getTacticalPositionPct()),
            "单股最大仓位不超过10%，单板块最大仓位不超过30%，总仓位不超过80%。"));
    addSection(
        sections,
        "风险与跟踪",
        List.of("若跌破止损价或板块主线热度明显降温，应降低仓位或退出观察。", "重点跟踪后续季度利润是否延续增长，以及价格是否重新回到建仓/确认区间。"));
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
    if (first.getNetProfit() != null
        && last.getNetProfit() != null
        && first.getNetProfit().compareTo(BigDecimal.ZERO) != 0) {
      BigDecimal change =
          last.getNetProfit()
              .subtract(first.getNetProfit())
              .divide(first.getNetProfit().abs(), 6, RoundingMode.HALF_UP)
              .multiply(BigDecimal.valueOf(100))
              .setScale(2, RoundingMode.HALF_UP);
      trend = "，区间变化 " + pct(change);
    }
    return "最近季度 "
        + last.getLabel()
        + " 单季净利润 "
        + money(last.getNetProfit())
        + "，环比 "
        + pct(last.getQoqPct())
        + trend
        + "。";
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
    List<TradeStockFinancial> records =
        financialRepo.findByStockCodeOrderByReportDateDesc(stockCode).stream()
            .filter(f -> f.getReportDate() != null && f.getNetProfit() != null)
            .sorted(Comparator.comparing(TradeStockFinancial::getReportDate))
            .toList();
    if (records.isEmpty()) return List.of();

    Map<java.time.LocalDate, TradeStockFinancial> byDate = new HashMap<>();
    for (TradeStockFinancial f : records) {
      byDate.put(f.getReportDate(), f);
    }

    List<QuarterProfit> quarters =
        records.stream().map(f -> toQuarterProfit(f, byDate)).filter(Objects::nonNull).toList();
    if (quarters.isEmpty()) return List.of();

    int from = Math.max(0, quarters.size() - 4);
    return quarters.subList(from, quarters.size()).stream()
        .map(
            q ->
                PickDailyDTO.ProfitQuarterDTO.builder()
                    .reportDate(q.reportDate())
                    .label(q.label())
                    .netProfit(q.netProfit())
                    .qoqPct(q.qoqPct())
                    .netMargin(q.netMargin())
                    .build())
        .toList();
  }

  private QuarterProfit toQuarterProfit(
      TradeStockFinancial f, Map<java.time.LocalDate, TradeStockFinancial> byDate) {
    int month = f.getReportDate().getMonthValue();
    BigDecimal quarterProfit = f.getNetProfit();
    if (month == 6 || month == 9 || month == 12) {
      java.time.LocalDate prev =
          switch (month) {
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
      if (prevQ != null
          && prevQ.netProfit() != null
          && prevQ.netProfit().compareTo(BigDecimal.ZERO) != 0) {
        qoq =
            quarterProfit
                .subtract(prevQ.netProfit())
                .divide(prevQ.netProfit().abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
      }
    }
    return new QuarterProfit(
        f.getReportDate(), quarterLabel(f.getReportDate()), quarterProfit, qoq, f.getNetMargin());
  }

  private QuarterProfit toQuarterProfitWithoutQoq(
      TradeStockFinancial f, Map<java.time.LocalDate, TradeStockFinancial> byDate) {
    int month = f.getReportDate().getMonthValue();
    BigDecimal quarterProfit = f.getNetProfit();
    if (month == 6 || month == 9 || month == 12) {
      java.time.LocalDate prev =
          switch (month) {
            case 6 -> java.time.LocalDate.of(f.getReportDate().getYear(), 3, 31);
            case 9 -> java.time.LocalDate.of(f.getReportDate().getYear(), 6, 30);
            default -> java.time.LocalDate.of(f.getReportDate().getYear(), 9, 30);
          };
      TradeStockFinancial prevRecord = byDate.get(prev);
      if (prevRecord != null && prevRecord.getNetProfit() != null) {
        quarterProfit = f.getNetProfit().subtract(prevRecord.getNetProfit());
      }
    }
    return new QuarterProfit(
        f.getReportDate(), quarterLabel(f.getReportDate()), quarterProfit, null, f.getNetMargin());
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
    int q =
        switch (date.getMonthValue()) {
          case 3 -> 1;
          case 6 -> 2;
          case 9 -> 3;
          default -> 4;
        };
    return String.format("%dQ%d", date.getYear(), q);
  }

  private record QuarterProfit(
      java.time.LocalDate reportDate,
      String label,
      BigDecimal netProfit,
      BigDecimal qoqPct,
      BigDecimal netMargin) {}
}
