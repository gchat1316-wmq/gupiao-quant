package com.quant.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.dto.invest.PoolItemDTO;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.SopCheckupDTO;
import com.quant.entity.InvestPositionCommon;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class InvestService {

  /**
   * 10×PS 估值三档的判定结果：
   *
   * <ul>
   *   <li>level — 低估 / 合理 / 泡沫 三档标签，或 null（无任何 Y 数据）
   *   <li>degree — 偏离参考年 10×PS 的百分比（−100~+∞），仅低估/泡沫给出
   *   <li>refYear — 参考年（2027=forecastY1 用作低估对照，2028=forecastY2 用作泡沫对照）
   * </ul>
   *
   * 同一个函数同时返回三段，避免前端/后端各算一份导致算法漂移。
   */
  public record ValuationVerdict(String level, BigDecimal degree, Integer refYear) {
    public static final ValuationVerdict EMPTY = new ValuationVerdict(null, null, null);
  }

  private final TradeStockBasicRepository stockBasicRepository;
  private final TradeStockFinancialRepository financialRepository;
  private final InvestStockPoolRepository poolRepository;
  private final InvestPositionCommonRepository positionRepository;
  private final AStockDataQuoteService aStockDataQuoteService;

  public InvestService(
      TradeStockBasicRepository stockBasicRepository,
      TradeStockFinancialRepository financialRepository,
      InvestStockPoolRepository poolRepository,
      InvestPositionCommonRepository positionRepository,
      AStockDataQuoteService aStockDataQuoteService) {
    this.stockBasicRepository = stockBasicRepository;
    this.financialRepository = financialRepository;
    this.poolRepository = poolRepository;
    this.positionRepository = positionRepository;
    this.aStockDataQuoteService = aStockDataQuoteService;
  }

  private static final String POOL_TYPE_INVEST = "invest";

  /** 获取持仓记录（若不存在则创建空白记录）。 */
  private InvestPositionCommon getOrCreatePosition(String stockCode) {
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

  // ===== 通用工具方法（供股票池 + SOP 共用）=====

  private BigDecimal calcYoy(BigDecimal current, BigDecimal prev) {
    if (current == null || prev == null || prev.compareTo(BigDecimal.ZERO) == 0) return null;
    return current
        .subtract(prev)
        .divide(prev.abs(), 4, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
  }

  private String prosperityLevel(BigDecimal yoy) {
    if (yoy == null) return "unknown";
    double v = yoy.doubleValue();
    if (v >= 30) return "high";
    if (v >= 5) return "medium";
    if (v >= 0) return "weak";
    return "low";
  }

  private String formatQuarter(LocalDate d) {
    int year = d.getYear() % 100;
    int q =
        switch (d.getMonthValue()) {
          case 3 -> 1;
          case 6 -> 2;
          case 9 -> 3;
          case 12 -> 4;
          default -> (d.getMonthValue() - 1) / 3 + 1;
        };
    return String.format("%02dQ%d", year, q);
  }

  private Optional<TradeStockBasic> resolveStock(String token) {
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

  private TradeStockBasic syntheticBasic(String code, String name) {
    TradeStockBasic b = new TradeStockBasic();
    b.setStockCode(code);
    b.setStockName(name);
    return b;
  }

  // ===== 股票池管理 =====

  /**
   * 列出股票池条目。poolType 为 null/blank 表示全部；否则按 poolType 在 DB 层过滤， 只为该 poolType 的代码拉实时行情 / 年初收盘价，避免一次切换
   * tab 时把所有池子的 行情和 K 线都拉一遍（外部 HTTP 调用是主要瓶颈）。
   *
   * <p>30s 缓存：行情 30s 内基本不变，反复切换 tab / 刷新页面直接走 cache。
   */
  @Cacheable(value = "stockPool", key = "#poolType == null ? 'all' : #poolType")
  @Transactional(readOnly = true)
  public List<PoolItemDTO> listPool(String poolType) {
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

  /** 兼容入口：列出全部股票池。 */
  public List<PoolItemDTO> listPool() {
    return listPool(null);
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

  @CacheEvict(value = "stockPool", allEntries = true)
  @Transactional
  public PoolItemDTO addToPool(PoolSaveRequest req) {
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

  @CacheEvict(value = "stockPool", allEntries = true)
  @Transactional
  public PoolItemDTO updatePool(Integer id, PoolSaveRequest req) {
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
  @CacheEvict(value = "stockPool", allEntries = true)
  @Transactional
  public PoolItemDTO updateField(Integer id, PoolFieldUpdateRequest req) {
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
      case "undervaluedPrice" -> pool.setUndervaluedPrice(parseDecimal(raw));
      case "fairPrice" -> pool.setFairPrice(parseDecimal(raw));
      case "overvaluedPrice" -> pool.setOvervaluedPrice(parseDecimal(raw));
      case "targetBuyPrice" -> {
        pool.setTargetBuyPrice(parseDecimal(raw));
        InvestPositionCommon pos = getOrCreatePosition(pool.getStockCode());
        pos.setAlertState("none");
        positionRepository.save(pos);
      }
      case "targetSellPrice" -> {
        InvestPositionCommon pos = getOrCreatePosition(pool.getStockCode());
        pos.setTargetSellPrice(parseDecimal(raw));
        pos.setAlertState("none");
        positionRepository.save(pos);
      }
      case "revenueForecastY0" -> pool.setRevenueForecastY0(parseDecimal(raw));
      case "revenueForecastY1" -> pool.setRevenueForecastY1(parseDecimal(raw));
      case "revenueForecastY2" -> pool.setRevenueForecastY2(parseDecimal(raw));
      case "revenue2023" -> pool.setRevenue2023(parseDecimal(raw));
      case "revenue2024" -> pool.setRevenue2024(parseDecimal(raw));
      case "revenue2025" -> pool.setRevenue2025(parseDecimal(raw));
      case "q1GrossMargin" -> pool.setQ1GrossMargin(parseDecimal(raw));
      case "q1NetMargin" -> pool.setQ1NetMargin(parseDecimal(raw));
      case "q1RevenueGrowth" -> pool.setQ1RevenueGrowth(parseDecimal(raw));
      case "minPs5y" -> pool.setMinPs5y(parseDecimal(raw));
      case "targetMarketCap" -> pool.setTargetMarketCap(parseDecimal(raw));
      case "displayOrder" -> pool.setDisplayOrder(blank ? null : Integer.parseInt(raw.trim()));
      case "profitLevel" -> pool.setProfitLevel(blank ? null : raw.trim());
      default -> throw new IllegalArgumentException("不支持的字段：" + field);
    }
    clearDerivedSnapshotFields(pool);
    return toPoolItemDTO(poolRepository.save(pool));
  }

  private BigDecimal parseDecimal(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("数值格式错误：" + raw);
    }
  }

  @CacheEvict(value = "stockPool", allEntries = true)
  @Transactional
  public void removeFromPool(Integer id) {
    poolRepository.deleteById(id);
  }

  /**
   * 批量更新股票池条目的 displayOrder（拖拽排序）。 入参每项至少要有 id 与 displayOrder；id 必须存在，displayOrder 必须 ≥ 0。 事务内串行执行
   * N 条 UPDATE，N 通常 ≤ 50，耗时可忽略。
   */
  @CacheEvict(value = "stockPool", allEntries = true)
  @Transactional
  public int reorder(List<ReorderItem> items) {
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
        computeYtdGain(latestPrice, ctx.yearStartCloseMap().get(normalizeCodeKey(code)));
    BigDecimal computedMarketCap =
        quote != null && quote.totalMarketCapYi() != null
            ? quote.totalMarketCapYi()
            : computeMarketCap(latestPrice, basic);
    ValuationVerdict valuationVerdict =
        inferValuationRange(
            computedMarketCap, pool.getRevenueForecastY1(), pool.getRevenueForecastY2());

    BigDecimal latestRevenueYoy = fin != null ? fin.getRevenueYoy() : null;
    BigDecimal latestProfitYoy = fin != null ? fin.getDeductedNetProfitYoy() : null;
    String latestLevel = prosperityLevel(latestRevenueYoy);

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

  private BigDecimal computeYtdGain(BigDecimal latestPrice, BigDecimal yearStartClose) {
    if (latestPrice == null
        || yearStartClose == null
        || yearStartClose.compareTo(BigDecimal.ZERO) == 0) return null;
    return latestPrice
        .subtract(yearStartClose)
        .divide(yearStartClose, 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal computeMarketCap(BigDecimal latestPrice, TradeStockBasic basic) {
    if (latestPrice == null || basic == null || basic.getTotalShares() == null) return null;
    BigDecimal totalShares = BigDecimal.valueOf(basic.getTotalShares());
    BigDecimal totalCap = totalShares.multiply(latestPrice);
    return totalCap.divide(BigDecimal.valueOf(100_000_000L), 2, RoundingMode.HALF_UP);
  }

  /**
   * 10×PS 估值三档 + 偏离百分比。
   *
   * <pre>
   *   合理市值   = 未来第 N 年预测营收 × 10
   *   低估       marketCap < Y1 × 10            → 27 -X%（X = mc/(Y1×10) - 1）
   *   泡沫       marketCap > Y2 × 10            → 28 +X%
   *   合理       Y1×10 ≤ mc ≤ Y2×10            → refYear=距 Y1/Y2 更近那年，degree=mc/ref-1
   * </pre>
   *
   * 短差用 Y1 拉得紧，长差用 Y2 给发展空间 —— 两套参照年分开避免单一指标拍脑袋。
   *
   * <p>2026-07-01 改：合理时也输出偏离，让用户知道是"刚过 Y1"（接近低估） 还是"快到 Y2"（接近泡沫）。思泰克 mc=113.7 vs Y2=118 只差 3.6%，
   * 显示"合理 28年 -3.64%"比裸"合理"直观得多。
   */
  static ValuationVerdict inferValuationRange(
      BigDecimal marketCap, BigDecimal revenueForecastY1, BigDecimal revenueForecastY2) {
    if (marketCap == null) return ValuationVerdict.EMPTY;
    BigDecimal fairCapY1 =
        revenueForecastY1 == null ? null : revenueForecastY1.multiply(BigDecimal.TEN);
    BigDecimal fairCapY2 =
        revenueForecastY2 == null ? null : revenueForecastY2.multiply(BigDecimal.TEN);

    if (fairCapY1 != null && marketCap.compareTo(fairCapY1) < 0) {
      return new ValuationVerdict("低估", degreeAgainst(marketCap, fairCapY1), 2027);
    }
    if (fairCapY2 != null && marketCap.compareTo(fairCapY2) > 0) {
      return new ValuationVerdict("泡沫", degreeAgainst(marketCap, fairCapY2), 2028);
    }
    if (fairCapY1 == null && fairCapY2 == null) {
      return ValuationVerdict.EMPTY;
    }

    // 合理区间：refYear = 相对距离 Y1/Y2 更近的那年（除以 fairCap 归一化）。
    // 思泰克: 相对距 Y1=22.9/90.8=25.2%, 相对距 Y2=4.3/118=3.6% → 选 Y2 → degree=-3.64%
    // 中点 mc=250, fairCapY1=200, fairCapY2=300: 相对距 Y1=50/200=25%, 相对距 Y2=50/300=16.7% → 选 Y2
    // 完全相等时默认选 Y1。
    BigDecimal distY1 =
        fairCapY1 == null
            ? null
            : marketCap
                .subtract(fairCapY1)
                .abs()
                .divide(fairCapY1, 6, java.math.RoundingMode.HALF_UP);
    BigDecimal distY2 =
        fairCapY2 == null
            ? null
            : marketCap
                .subtract(fairCapY2)
                .abs()
                .divide(fairCapY2, 6, java.math.RoundingMode.HALF_UP);

    if (fairCapY1 == null) {
      return new ValuationVerdict("合理", degreeAgainst(marketCap, fairCapY2), 2028);
    }
    if (fairCapY2 == null) {
      return new ValuationVerdict("合理", degreeAgainst(marketCap, fairCapY1), 2027);
    }
    if (distY1.compareTo(distY2) <= 0) {
      return new ValuationVerdict("合理", degreeAgainst(marketCap, fairCapY1), 2027);
    }
    return new ValuationVerdict("合理", degreeAgainst(marketCap, fairCapY2), 2028);
  }

  /**
   * 当前市值相对 fairCap 偏离百分比 = mc / fairCap - 1，结果乘 100 取两位小数。 例：mc=150, fairCap=100 → +50.00；mc=50,
   * fairCap=100 → -50.00。
   */
  private static BigDecimal degreeAgainst(BigDecimal marketCap, BigDecimal fairCap) {
    BigDecimal ratio = marketCap.divide(fairCap, 6, RoundingMode.HALF_UP);
    BigDecimal percent = ratio.multiply(BigDecimal.valueOf(100)).subtract(BigDecimal.valueOf(100));
    return percent.setScale(2, RoundingMode.HALF_UP);
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

  // ===== 实战选股 SOP · 三大数字体检 =====

  private static final int SOP_QUARTERS = 8;

  @Cacheable(value = "sopCheckup", key = "#keyword")
  @Transactional(readOnly = true)
  public SopCheckupDTO sopCheckup(String keyword) {
    Optional<TradeStockBasic> infoOpt = resolveStock(keyword == null ? "" : keyword.trim());
    if (infoOpt.isEmpty()) {
      return SopCheckupDTO.builder()
          .matched(false)
          .message("未找到股票：" + keyword + "（请输入6位代码或完整名称）")
          .build();
    }
    TradeStockBasic info = infoOpt.get();
    List<TradeStockFinancial> all =
        financialRepository.findByStockCodeOrderByReportDateDesc(info.getStockCode());
    if (all.isEmpty()) {
      return SopCheckupDTO.builder()
          .matched(false)
          .stockCode(info.getStockCode())
          .stockName(info.getStockName())
          .message("暂无该股票的财务数据")
          .build();
    }
    Map<LocalDate, TradeStockFinancial> dateMap =
        all.stream()
            .collect(Collectors.toMap(TradeStockFinancial::getReportDate, r -> r, (a, b) -> a));
    List<TradeStockFinancial> asc =
        new ArrayList<>(all.stream().limit(SOP_QUARTERS).collect(Collectors.toList()));
    java.util.Collections.reverse(asc);

    SopCheckupDTO.MetricCheck gross = buildGrossMargin(asc);
    SopCheckupDTO.MetricCheck rev = buildRevenueYoy(asc, dateMap);
    SopCheckupDTO.MetricCheck profit = buildProfitYoy(asc, dateMap, rev.getLatest());

    String overall = combineVerdict(gross.getVerdict(), rev.getVerdict(), profit.getVerdict());
    String summary =
        switch (overall) {
          case "pass" -> "三大数字全部通过 ✓ 投资逻辑被财报印证，可重点跟踪";
          case "warn" -> "部分指标偏弱 ⚠ 建议再观察 1-2 个季度";
          default -> "数字不漂亮 ✗ 谨慎对待，可能存在基本面瑕疵";
        };

    return SopCheckupDTO.builder()
        .matched(true)
        .stockCode(info.getStockCode())
        .stockName(info.getStockName())
        .grossMargin(gross)
        .revenueYoy(rev)
        .profitYoy(profit)
        .overallVerdict(overall)
        .overallSummary(summary)
        .build();
  }

  private SopCheckupDTO.MetricCheck buildGrossMargin(List<TradeStockFinancial> asc) {
    List<SopCheckupDTO.QuarterPoint> series = new ArrayList<>();
    BigDecimal latest = null;
    BigDecimal first = null;
    for (TradeStockFinancial f : asc) {
      BigDecimal gm = f.getGrossMargin();
      series.add(
          SopCheckupDTO.QuarterPoint.builder()
              .quarter(formatQuarter(f.getReportDate()))
              .value(gm)
              .build());
      if (gm != null) {
        if (first == null) first = gm;
        latest = gm;
      }
    }
    String verdict;
    String tip;
    if (latest == null || first == null) {
      verdict = "warn";
      tip = "缺少毛利率数据";
    } else {
      double d = latest.subtract(first).doubleValue();
      if (d >= 0.5) {
        verdict = "pass";
        tip =
            String.format("毛利率从 %.1f%% 提升到 %.1f%%，定价权强", first.doubleValue(), latest.doubleValue());
      } else if (d >= -1.0) {
        verdict = "pass";
        tip = String.format("毛利率稳定在 %.1f%% 附近，护城河稳固", latest.doubleValue());
      } else if (d >= -3.0) {
        verdict = "warn";
        tip = String.format("毛利率下滑 %.1f 个百分点，需关注是否价格战", -d);
      } else {
        verdict = "fail";
        tip = String.format("毛利率大幅下滑 %.1f 个百分点，护城河可能被侵蚀", -d);
      }
    }
    return SopCheckupDTO.MetricCheck.builder()
        .label("毛利率")
        .unit("%")
        .series(series)
        .latest(latest)
        .verdict(verdict)
        .tip(tip)
        .build();
  }

  private SopCheckupDTO.MetricCheck buildRevenueYoy(
      List<TradeStockFinancial> asc, Map<LocalDate, TradeStockFinancial> dateMap) {
    List<SopCheckupDTO.QuarterPoint> series = new ArrayList<>();
    BigDecimal latest = null;
    int highCount = 0, valid = 0;
    for (TradeStockFinancial f : asc) {
      BigDecimal yoy = f.getRevenueYoy();
      if (yoy == null) {
        TradeStockFinancial prev = dateMap.get(f.getReportDate().minusYears(1));
        yoy = calcYoy(f.getRevenue(), prev != null ? prev.getRevenue() : null);
      }
      series.add(
          SopCheckupDTO.QuarterPoint.builder()
              .quarter(formatQuarter(f.getReportDate()))
              .value(yoy)
              .build());
      if (yoy != null) {
        valid++;
        latest = yoy;
        if (yoy.doubleValue() >= 20) highCount++;
      }
    }
    String verdict, tip;
    if (valid == 0 || latest == null) {
      verdict = "warn";
      tip = "缺少营收同比数据";
    } else {
      double ratio = highCount * 1.0 / valid;
      if (latest.doubleValue() >= 20 && ratio >= 0.6) {
        verdict = "pass";
        tip =
            String.format(
                "最新营收 +%.1f%%，%d/%d 个季度 ≥ 20%%，持续高增长", latest.doubleValue(), highCount, valid);
      } else if (latest.doubleValue() >= 10) {
        verdict = "warn";
        tip = String.format("最新营收 +%.1f%%，未达 20%% 高增长线", latest.doubleValue());
      } else {
        verdict = "fail";
        tip = String.format("最新营收 %.1f%%，增长乏力", latest.doubleValue());
      }
    }
    return SopCheckupDTO.MetricCheck.builder()
        .label("营收同比")
        .unit("%")
        .series(series)
        .latest(latest)
        .verdict(verdict)
        .tip(tip)
        .build();
  }

  private SopCheckupDTO.MetricCheck buildProfitYoy(
      List<TradeStockFinancial> asc,
      Map<LocalDate, TradeStockFinancial> dateMap,
      BigDecimal latestRevenueYoy) {
    List<SopCheckupDTO.QuarterPoint> series = new ArrayList<>();
    BigDecimal latest = null;
    for (TradeStockFinancial f : asc) {
      BigDecimal py = f.getDeductedNetProfitYoy();
      if (py == null) {
        TradeStockFinancial prev = dateMap.get(f.getReportDate().minusYears(1));
        py = calcYoy(f.getNetProfit(), prev != null ? prev.getNetProfit() : null);
      }
      series.add(
          SopCheckupDTO.QuarterPoint.builder()
              .quarter(formatQuarter(f.getReportDate()))
              .value(py)
              .build());
      if (py != null) latest = py;
    }
    String verdict, tip;
    if (latest == null) {
      verdict = "warn";
      tip = "缺少扣非净利润同比数据";
    } else if (latestRevenueYoy == null) {
      verdict = latest.doubleValue() >= 20 ? "pass" : (latest.doubleValue() >= 0 ? "warn" : "fail");
      tip = String.format("最新扣非 %+.1f%%", latest.doubleValue());
    } else {
      double diff = latest.doubleValue() - latestRevenueYoy.doubleValue();
      if (diff >= 5 && latest.doubleValue() >= 0) {
        verdict = "pass";
        tip =
            String.format(
                "扣非 +%.1f%% > 营收 +%.1f%%，规模效应显著",
                latest.doubleValue(), latestRevenueYoy.doubleValue());
      } else if (latest.doubleValue() >= 0 && diff >= -5) {
        verdict = "warn";
        tip =
            String.format(
                "扣非 %+.1f%% 与营收 %+.1f%% 基本同步，盈利能力未提升",
                latest.doubleValue(), latestRevenueYoy.doubleValue());
      } else {
        verdict = "fail";
        tip =
            String.format(
                "扣非 %+.1f%% 落后营收 %+.1f%%，规模不经济",
                latest.doubleValue(), latestRevenueYoy.doubleValue());
      }
    }
    return SopCheckupDTO.MetricCheck.builder()
        .label("扣非净利润同比")
        .unit("%")
        .series(series)
        .latest(latest)
        .verdict(verdict)
        .tip(tip)
        .build();
  }

  private String combineVerdict(String... vs) {
    int pass = 0, warn = 0, fail = 0;
    for (String v : vs) {
      if ("pass".equals(v)) pass++;
      else if ("warn".equals(v)) warn++;
      else if ("fail".equals(v)) fail++;
    }
    if (fail >= 1) return "fail";
    if (pass == vs.length) return "pass";
    if (warn >= 2) return "fail";
    return "warn";
  }
}
