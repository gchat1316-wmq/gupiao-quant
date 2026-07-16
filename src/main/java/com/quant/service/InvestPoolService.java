package com.quant.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolItemDTO;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 投资池（龙江/谢博）股票池管理：增删改查、内联单字段编辑、拖拽排序，以及 {@link InvestStockPool} → {@link PoolItemDTO} 的富化转换
 * （拉取实时行情、年初收盘价、财务快照、估值三档）。
 *
 * <p>缓存/事务边界由 {@link InvestService} 门面持有，本服务只做纯业务逻辑；股票解析 {@link #resolveStock} 亦被 {@link
 * InvestSopService} 复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvestPoolService {

  static final String POOL_TYPE_INVEST = "invest";

  private final TradeStockBasicRepository stockBasicRepository;
  private final TradeStockFinancialRepository financialRepository;
  private final InvestStockPoolRepository poolRepository;
  private final InvestPositionCommonRepository positionRepository;
  private final AStockDataQuoteService aStockDataQuoteService;
  private final InvestValuationService valuationService;

  /** 获取持仓记录（若不存在则创建空白记录）。 */
  InvestPositionCommon getOrCreatePosition(String stockCode) {
    return positionRepository
        .findByStockCodeAndPoolType(stockCode, POOL_TYPE_INVEST)
        .orElseGet(
            () -> {
              InvestPositionCommon pos = new InvestPositionCommon();
              pos.setStockCode(stockCode);
              pos.setPoolType(POOL_TYPE_INVEST);
              pos.setStatus("watching");
              pos.setAlertState("none");
              pos.setPositionState("none");
              pos.setPositionLots(BigDecimal.ZERO);
              pos.setRealizedPnl(BigDecimal.ZERO);
              pos.setAddCount(0);
              pos.setTakeProfitDone(0);
              pos.setBreakevenAfterTp(1);
              pos.setUseAtr(0);
              return pos;
            });
  }

  // ===== 股票解析（供股票池 + SOP 共用）=====

  Optional<TradeStockBasic> resolveStock(String token) {
    String t = token.trim();
    if (t.isEmpty()) return Optional.empty();

    String bareCode = t.contains(".") ? t.substring(0, t.indexOf('.')) : t;

    if (bareCode.matches("\\d{4,8}")) {
      Optional<TradeStockBasic> byFull = stockBasicRepository.findByStockCode(t);
      if (byFull.isPresent()) return byFull;
      List<TradeStockBasic> byPrefix = stockBasicRepository.findByStockCodePrefix(bareCode);
      if (!byPrefix.isEmpty()) return Optional.of(byPrefix.get(0));
      List<TradeStockFinancial> fin = financialRepository.findByStockCodeOrderByReportDateDesc(t);
      if (!fin.isEmpty()) {
        String finName = fin.get(0).getStockName();
        return Optional.of(syntheticBasic(t, finName != null && !finName.isBlank() ? finName : t));
      }
    }
    List<TradeStockBasic> byName = stockBasicRepository.findByStockNameLike(t);
    if (!byName.isEmpty()) return Optional.of(byName.get(0));
    List<TradeStockFinancial> finByName = financialRepository.findByStockNameLike(t);
    if (!finByName.isEmpty()) {
      TradeStockFinancial first = finByName.get(0);
      String finName = first.getStockName();
      return Optional.of(
          syntheticBasic(
              first.getStockCode(),
              finName != null && !finName.isBlank() ? finName : first.getStockCode()));
    }
    return Optional.empty();
  }

  TradeStockBasic syntheticBasic(String code, String name) {
    TradeStockBasic b = new TradeStockBasic();
    b.setStockCode(code);
    b.setStockName(name);
    return b;
  }

  // ===== 股票池管理 =====

  List<PoolItemDTO> listPool(String poolType) {
    boolean all = (poolType == null || poolType.isBlank());
    List<InvestStockPool> items =
        all
            ? poolRepository.findAllByOrderByCreatedAtDesc()
            : poolRepository.findByPoolTypeOrderByCreatedAtDesc(poolType);
    if (items.isEmpty()) return List.of();

    List<String> codes =
        items.stream().map(InvestStockPool::getStockCode).collect(Collectors.toList());

    Map<String, TradeStockBasic> basicMap =
        stockBasicRepository.findByStockCodeIn(expandCodeVariants(codes)).stream()
            .collect(
                Collectors.toMap(f -> normalizeCodeKey(f.getStockCode()), b -> b, (a, b) -> a));

    Map<String, TradeStockFinancial> finMap =
        financialRepository.findLatestByStockCodes(codes).stream()
            .collect(Collectors.toMap(TradeStockFinancial::getStockCode, f -> f));

    LocalDate yearStart = LocalDate.of(LocalDate.now().getYear(), 1, 1);
    Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap =
        aStockDataQuoteService.fetchQuotes(codes).values().stream()
            .collect(
                Collectors.toMap(
                    snapshot -> normalizeCodeKey(snapshot.stockCode()),
                    snapshot -> snapshot,
                    (a, b) -> a));
    Map<String, BigDecimal> yearStartCloseMap =
        aStockDataQuoteService.fetchYearStartCloses(codes, yearStart);

    PoolPriceContext ctx = new PoolPriceContext(basicMap, finMap, quoteMap, yearStartCloseMap);
    return items.stream()
        .sorted(poolDisplayComparator())
        .map(p -> toPoolItemDTO(p, ctx))
        .collect(Collectors.toList());
  }

  private Comparator<InvestStockPool> poolDisplayComparator() {
    // 优先级：tech_ai (0) < innovative_drug (1) < quality (2)；同池内按 displayOrder，再按创建时间倒序
    return Comparator.comparingInt((InvestStockPool p) -> poolTypePriority(p.getPoolType()))
        .thenComparing(p -> p.getDisplayOrder() == null ? Integer.MAX_VALUE : p.getDisplayOrder())
        .thenComparing(
            InvestStockPool::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
  }

  private static int poolTypePriority(String poolType) {
    if ("tech_ai".equals(poolType)) return 0;
    if ("innovative_drug".equals(poolType)) return 1;
    if ("quality".equals(poolType)) return 2;
    return 9;
  }

  /** poolType → 中文标签。tech_ai 在 DB 中仍是 "tech_ai"，显示为"科技AI"。 */
  public static String poolTypeLabelOf(String poolType) {
    if ("quality".equals(poolType)) return "质量优选";
    if ("tech_ai".equals(poolType)) return "科技AI";
    if ("innovative_drug".equals(poolType)) return "创新药";
    return poolType == null ? "" : poolType;
  }

  private record PoolPriceContext(
      Map<String, TradeStockBasic> basicMap,
      Map<String, TradeStockFinancial> finMap,
      Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap,
      Map<String, BigDecimal> yearStartCloseMap) {}

  private Set<String> expandCodeVariants(List<String> codes) {
    Set<String> variants = new LinkedHashSet<>();
    for (String code : codes) {
      if (code == null || code.isBlank()) continue;
      variants.add(code);
      variants.add(code.toUpperCase(Locale.ROOT));
    }
    return variants;
  }

  private String normalizeCodeKey(String code) {
    return code == null ? "" : code.toUpperCase(Locale.ROOT);
  }

  PoolItemDTO addToPool(PoolSaveRequest req) {
    String kw = req.getKeyword() == null ? "" : req.getKeyword().trim();
    Optional<TradeStockBasic> infoOpt = resolveStock(kw);
    // 最后兜底：纯数字代码格式直接放行（财务数据将来会有）
    if (infoOpt.isEmpty() && kw.matches("\\d{4,8}")) {
      infoOpt = Optional.of(syntheticBasic(kw, kw));
    }
    if (infoOpt.isEmpty()) {
      throw new IllegalArgumentException("未找到股票：" + kw + "（请输入6位股票代码或完整名称）");
    }
    TradeStockBasic info = infoOpt.get();
    if (poolRepository.findByStockCode(info.getStockCode()).isPresent()) {
      throw new IllegalArgumentException("该股票已在股票池中：" + info.getStockName());
    }

    InvestStockPool pool = new InvestStockPool();
    pool.setStockCode(info.getStockCode());
    pool.setStockName(info.getStockName());
    pool.setPoolType(req.getPoolType() != null ? req.getPoolType() : "quality");
    applyPoolFields(pool, req);

    InvestStockPool saved = poolRepository.save(pool);
    InvestPositionCommon position = getOrCreatePosition(saved.getStockCode());
    position.setStatus(req.getStatus() != null ? req.getStatus() : "watching");
    if (req.getTargetSellPrice() != null) position.setTargetSellPrice(req.getTargetSellPrice());
    positionRepository.save(position);
    return toPoolItemDTO(saved);
  }

  PoolItemDTO updatePool(Integer id, PoolSaveRequest req) {
    InvestStockPool pool =
        poolRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("股票池条目不存在：" + id));
    if (req.getPoolType() != null) pool.setPoolType(req.getPoolType());
    InvestPositionCommon pos = getOrCreatePosition(pool.getStockCode());
    if (req.getStatus() != null) {
      pos.setStatus(req.getStatus());
    }
    if (req.getTargetSellPrice() != null) {
      pos.setTargetSellPrice(req.getTargetSellPrice());
    }

    // 2026-07-01 弹窗"消息监控" checkbox：勾选时把目标价同步成 fixed_buy/sell_price，
    // MonitorService 扫描 InvestPositionCommon 触发 server 酱推送。
    // null = 用户没动开关 → 保持现状；true = 开启并同步价格；false = 关闭并清空价格。
    if (req.getAlertBuyEnabled() != null) {
      if (req.getAlertBuyEnabled()) {
        pos.setFixedBuyEnabled(1);
        pos.setFixedBuyPrice(pool.getTargetBuyPrice());
      } else {
        pos.setFixedBuyEnabled(0);
        pos.setFixedBuyPrice(null);
      }
    }
    if (req.getAlertSellEnabled() != null) {
      if (req.getAlertSellEnabled()) {
        pos.setFixedSellEnabled(1);
        pos.setFixedSellPrice(pos.getTargetSellPrice());
      } else {
        pos.setFixedSellEnabled(0);
        pos.setFixedSellPrice(null);
      }
    }

    positionRepository.save(pos);
    applyPoolFields(pool, req);
    return toPoolItemDTO(poolRepository.save(pool));
  }

  private void applyPoolFields(InvestStockPool pool, PoolSaveRequest req) {
    if (req.getMemo() != null) pool.setMemo(req.getMemo());
    if (req.getTargetPrice() != null) pool.setTargetPrice(req.getTargetPrice());
    if (req.getUndervaluedPrice() != null) pool.setUndervaluedPrice(req.getUndervaluedPrice());
    if (req.getFairPrice() != null) pool.setFairPrice(req.getFairPrice());
    if (req.getOvervaluedPrice() != null) pool.setOvervaluedPrice(req.getOvervaluedPrice());
    if (req.getTargetBuyPrice() != null) pool.setTargetBuyPrice(req.getTargetBuyPrice());
    if (req.getRevenueForecastY0() != null) pool.setRevenueForecastY0(req.getRevenueForecastY0());
    if (req.getRevenueForecastY1() != null) pool.setRevenueForecastY1(req.getRevenueForecastY1());
    if (req.getRevenueForecastY2() != null) pool.setRevenueForecastY2(req.getRevenueForecastY2());
    if (req.getRevenue2023() != null) pool.setRevenue2023(req.getRevenue2023());
    if (req.getRevenue2024() != null) pool.setRevenue2024(req.getRevenue2024());
    if (req.getRevenue2025() != null) pool.setRevenue2025(req.getRevenue2025());
    if (req.getQ1GrossMargin() != null) pool.setQ1GrossMargin(req.getQ1GrossMargin());
    if (req.getQ1NetMargin() != null) pool.setQ1NetMargin(req.getQ1NetMargin());
    if (req.getQ1RevenueGrowth() != null) pool.setQ1RevenueGrowth(req.getQ1RevenueGrowth());
    if (req.getMinPs5y() != null) pool.setMinPs5y(req.getMinPs5y());
    if (req.getTargetMarketCap() != null) pool.setTargetMarketCap(req.getTargetMarketCap());
    if (req.getDisplayOrder() != null) pool.setDisplayOrder(req.getDisplayOrder());
    if (req.getProfitLevel() != null) pool.setProfitLevel(req.getProfitLevel());
    clearDerivedSnapshotFields(pool);
  }

  /** 单字段更新（内联编辑）。空字符串视为清空（设为 null），允许撤销字段值。 */
  PoolItemDTO updateField(Integer id, PoolFieldUpdateRequest req) {
    if (req == null || req.getField() == null || req.getField().isBlank()) {
      throw new IllegalArgumentException("字段名不能为空");
    }
    InvestStockPool pool =
        poolRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("股票池条目不存在：" + id));
    String field = req.getField().trim();
    String raw = req.getValue();
    boolean blank = raw == null || raw.isBlank();
    switch (field) {
      case "poolType" -> pool.setPoolType(blank ? "quality" : raw.trim());
      case "status" -> {
        String v = blank ? "watching" : raw.trim();
        InvestPositionCommon pos = getOrCreatePosition(pool.getStockCode());
        pos.setStatus(v);
        if (!"watching".equals(pos.getAlertState()) && "exited".equals(v)) {
          pos.setAlertState("none");
        }
        positionRepository.save(pos);
      }
      case "memo" -> pool.setMemo(blank ? null : raw);
      case "undervaluedPrice" -> pool.setUndervaluedPrice(InvestMathUtils.parseDecimal(raw));
      case "fairPrice" -> pool.setFairPrice(InvestMathUtils.parseDecimal(raw));
      case "overvaluedPrice" -> pool.setOvervaluedPrice(InvestMathUtils.parseDecimal(raw));
      case "targetBuyPrice" -> {
        pool.setTargetBuyPrice(InvestMathUtils.parseDecimal(raw));
        InvestPositionCommon pos = getOrCreatePosition(pool.getStockCode());
        pos.setAlertState("none");
        positionRepository.save(pos);
      }
      case "targetSellPrice" -> {
        InvestPositionCommon pos = getOrCreatePosition(pool.getStockCode());
        pos.setTargetSellPrice(InvestMathUtils.parseDecimal(raw));
        pos.setAlertState("none");
        positionRepository.save(pos);
      }
      case "revenueForecastY0" -> pool.setRevenueForecastY0(InvestMathUtils.parseDecimal(raw));
      case "revenueForecastY1" -> pool.setRevenueForecastY1(InvestMathUtils.parseDecimal(raw));
      case "revenueForecastY2" -> pool.setRevenueForecastY2(InvestMathUtils.parseDecimal(raw));
      case "revenue2023" -> pool.setRevenue2023(InvestMathUtils.parseDecimal(raw));
      case "revenue2024" -> pool.setRevenue2024(InvestMathUtils.parseDecimal(raw));
      case "revenue2025" -> pool.setRevenue2025(InvestMathUtils.parseDecimal(raw));
      case "q1GrossMargin" -> pool.setQ1GrossMargin(InvestMathUtils.parseDecimal(raw));
      case "q1NetMargin" -> pool.setQ1NetMargin(InvestMathUtils.parseDecimal(raw));
      case "q1RevenueGrowth" -> pool.setQ1RevenueGrowth(InvestMathUtils.parseDecimal(raw));
      case "minPs5y" -> pool.setMinPs5y(InvestMathUtils.parseDecimal(raw));
      case "targetMarketCap" -> pool.setTargetMarketCap(InvestMathUtils.parseDecimal(raw));
      case "displayOrder" -> pool.setDisplayOrder(blank ? null : Integer.parseInt(raw.trim()));
      case "profitLevel" -> pool.setProfitLevel(blank ? null : raw.trim());
      default -> throw new IllegalArgumentException("不支持的字段：" + field);
    }
    clearDerivedSnapshotFields(pool);
    return toPoolItemDTO(poolRepository.save(pool));
  }

  void removeFromPool(Integer id) {
    poolRepository.deleteById(id);
  }

  /**
   * 批量更新股票池条目的 displayOrder（拖拽排序）。 入参每项至少要有 id 与 displayOrder；id 必须存在，displayOrder 必须 ≥ 0。 事务内串行执行
   * N 条 UPDATE，N 通常 ≤ 50，耗时可忽略。
   */
  int reorder(List<ReorderItem> items) {
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException("排序项不能为空");
    }
    int updated = 0;
    for (ReorderItem item : items) {
      if (item == null || item.getId() == null) {
        throw new IllegalArgumentException("排序项 id 不能为空");
      }
      if (item.getDisplayOrder() == null || item.getDisplayOrder() < 0) {
        throw new IllegalArgumentException("displayOrder 必须 ≥ 0：" + item.getId());
      }
      // 跳过不存在的 id（防御：前端可能传了已删除的条目），但要警告
      if (!poolRepository.existsById(item.getId())) {
        log.warn("reorder 跳过不存在的 id: {}", item.getId());
        continue;
      }
      poolRepository.updateDisplayOrder(item.getId(), item.getDisplayOrder());
      updated++;
    }
    return updated;
  }

  /** 拖拽排序的单条请求项。 */
  public static class ReorderItem {
    private Integer id;
    private Integer displayOrder;

    public ReorderItem() {}

    public ReorderItem(Integer id, Integer displayOrder) {
      this.id = id;
      this.displayOrder = displayOrder;
    }

    public Integer getId() {
      return id;
    }

    public void setId(Integer id) {
      this.id = id;
    }

    public Integer getDisplayOrder() {
      return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
      this.displayOrder = displayOrder;
    }
  }

  /** 单条转换，供 addToPool / updatePool / updateField 使用。 */
  private PoolItemDTO toPoolItemDTO(InvestStockPool pool) {
    String code = pool.getStockCode();
    TradeStockBasic basic = findBasicByPoolCode(code);
    TradeStockFinancial fin =
        financialRepository.findByStockCodeOrderByReportDateDesc(code).stream()
            .findFirst()
            .orElse(null);
    LocalDate yearStart = LocalDate.of(LocalDate.now().getYear(), 1, 1);
    AStockDataQuoteService.QuoteSnapshot quote =
        aStockDataQuoteService.fetchQuotes(List.of(code)).values().stream()
            .findFirst()
            .orElse(null);
    BigDecimal yearStartClose =
        aStockDataQuoteService
            .fetchYearStartCloses(List.of(code), yearStart)
            .get(normalizeCodeKey(code));

    Map<String, TradeStockBasic> basicMap =
        basic != null ? Map.of(normalizeCodeKey(code), basic) : Map.of();
    Map<String, TradeStockFinancial> finMap = fin != null ? Map.of(code, fin) : Map.of();
    Map<String, AStockDataQuoteService.QuoteSnapshot> quoteMap =
        quote != null ? Map.of(normalizeCodeKey(code), quote) : Map.of();
    Map<String, BigDecimal> yearStartCloseMap =
        yearStartClose != null ? Map.of(normalizeCodeKey(code), yearStartClose) : Map.of();
    return toPoolItemDTO(pool, new PoolPriceContext(basicMap, finMap, quoteMap, yearStartCloseMap));
  }

  private PoolItemDTO toPoolItemDTO(InvestStockPool pool, PoolPriceContext ctx) {
    String code = pool.getStockCode();
    TradeStockBasic basic = ctx.basicMap().get(normalizeCodeKey(code));
    String stockName = displayStockName(pool, basic);
    TradeStockFinancial fin = ctx.finMap().get(code);
    AStockDataQuoteService.QuoteSnapshot quote = ctx.quoteMap().get(normalizeCodeKey(code));

    BigDecimal latestPrice = quote == null ? null : quote.latestPrice();
    BigDecimal ytdGain =
        valuationService.computeYtdGain(
            latestPrice, ctx.yearStartCloseMap().get(normalizeCodeKey(code)));
    BigDecimal computedMarketCap =
        quote != null && quote.totalMarketCapYi() != null
            ? quote.totalMarketCapYi()
            : valuationService.computeMarketCap(latestPrice, basic);
    InvestValuationService.ValuationVerdict valuationVerdict =
        InvestValuationService.inferValuationRange(
            computedMarketCap, pool.getRevenueForecastY1(), pool.getRevenueForecastY2());

    BigDecimal latestRevenueYoy = fin != null ? fin.getRevenueYoy() : null;
    BigDecimal latestProfitYoy = fin != null ? fin.getDeductedNetProfitYoy() : null;
    String latestLevel = InvestMathUtils.prosperityLevel(latestRevenueYoy);

    // 从 invest_position_common 读取持仓/告警状态
    InvestPositionCommon position =
        positionRepository.findByStockCodeAndPoolType(code, POOL_TYPE_INVEST).orElse(null);

    return PoolItemDTO.builder()
        .id(pool.getId())
        .stockCode(code)
        .stockName(stockName)
        .poolType(pool.getPoolType())
        .poolTypeLabel(poolTypeLabelOf(pool.getPoolType()))
        .memo(pool.getMemo())
        .undervaluedPrice(pool.getUndervaluedPrice())
        .fairPrice(pool.getFairPrice())
        .overvaluedPrice(pool.getOvervaluedPrice())
        .targetBuyPrice(pool.getTargetBuyPrice())
        .targetSellPrice(position != null ? position.getTargetSellPrice() : null)
        .targetPrice(pool.getTargetPrice())
        .revenueForecastY0(pool.getRevenueForecastY0())
        .revenueForecastY1(pool.getRevenueForecastY1())
        .revenueForecastY2(pool.getRevenueForecastY2())
        .revenue2023(pool.getRevenue2023())
        .revenue2024(pool.getRevenue2024())
        .revenue2025(pool.getRevenue2025())
        .q1GrossMargin(pool.getQ1GrossMargin())
        .q1NetMargin(pool.getQ1NetMargin())
        .q1RevenueGrowth(pool.getQ1RevenueGrowth())
        .minPs5y(pool.getMinPs5y())
        .targetMarketCap(pool.getTargetMarketCap())
        .currentMarketCap(computedMarketCap)
        .ytdGainPct(ytdGain)
        .displayOrder(pool.getDisplayOrder())
        .poolUpdateError(pool.getPoolUpdateError())
        .profitLevel(pool.getProfitLevel())
        .valuationRange(valuationVerdict.level())
        .valuationDegree(valuationVerdict.degree())
        .valuationRefYear(valuationVerdict.refYear())
        .status(position != null ? position.getStatus() : null)
        .statusLabel(statusLabel(position != null ? position.getStatus() : null))
        .alertState(position != null ? position.getAlertState() : null)
        .lastAlertAt(position != null ? position.getLastAlertAt() : null)
        .latestPrice(latestPrice)
        .ytdGain(ytdGain)
        .marketCap(computedMarketCap)
        .latestRevenueYoy(latestRevenueYoy)
        .latestProfitYoy(latestProfitYoy)
        .latestLevel(latestLevel)
        .createdAt(pool.getCreatedAt())
        .updatedAt(pool.getUpdatedAt())
        .build();
  }

  private void clearDerivedSnapshotFields(InvestStockPool pool) {
    pool.setCurrentMarketCap(null);
    pool.setYtdGainPct(null);
    pool.setValuationRange(null);
  }

  private TradeStockBasic findBasicByPoolCode(String code) {
    Optional<TradeStockBasic> exact = stockBasicRepository.findByStockCode(code);
    if (exact.isPresent() || code == null) return exact.orElse(null);
    String upperCode = code.toUpperCase(Locale.ROOT);
    if (upperCode.equals(code)) return null;
    return stockBasicRepository.findByStockCode(upperCode).orElse(null);
  }

  private String displayStockName(InvestStockPool pool, TradeStockBasic basic) {
    if (basic != null && basic.getStockName() != null && !basic.getStockName().isBlank()) {
      return basic.getStockName();
    }
    if (pool.getStockName() != null && !pool.getStockName().isBlank()) {
      return pool.getStockName();
    }
    return pool.getStockCode();
  }

  private String statusLabel(String status) {
    if (status == null) {
      return "观察中";
    }
    return switch (status) {
      case "holding" -> "持仓中";
      case "exited" -> "已离场";
      default -> "观察中";
    };
  }
}
