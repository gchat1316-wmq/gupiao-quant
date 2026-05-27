package com.quant.service;

import com.quant.dto.invest.PoolItemDTO;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.ProsperityQuarterDTO;
import com.quant.dto.invest.ProsperityResultDTO;
import com.quant.dto.invest.ProsperityStockDTO;
import com.quant.dto.invest.SopCheckupDTO;
import com.quant.dto.invest.PoolFieldUpdateRequest;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.repository.TradeStockFinancialRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class InvestService {

    private static final int DEFAULT_QUARTERS = 8;
    private static final int MAX_QUARTERS = 16;

    private final TradeStockBasicRepository stockBasicRepository;
    private final TradeStockFinancialRepository financialRepository;
    private final TradeStockDailyRepository dailyRepository;
    private final InvestStockPoolRepository poolRepository;

    public InvestService(TradeStockBasicRepository stockBasicRepository,
                         TradeStockFinancialRepository financialRepository,
                         TradeStockDailyRepository dailyRepository,
                         InvestStockPoolRepository poolRepository) {
        this.stockBasicRepository = stockBasicRepository;
        this.financialRepository = financialRepository;
        this.dailyRepository = dailyRepository;
        this.poolRepository = poolRepository;
    }

    // ===== 景气度扫描 =====

    @Cacheable(value = "prosperity", key = "#keywords + '_' + (#quarters ?: 0)")
    @Transactional(readOnly = true)
    public ProsperityResultDTO queryProsperity(String keywords, Integer quarters) {
        int limit = (quarters == null || quarters <= 0) ? DEFAULT_QUARTERS : Math.min(quarters, MAX_QUARTERS);
        List<String> tokens = parseKeywords(keywords);

        List<ProsperityStockDTO> stocks = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        Map<String, String> allQuarterDates = new LinkedHashMap<>();

        for (String token : tokens) {
            Optional<TradeStockBasic> infoOpt = resolveStock(token);
            if (infoOpt.isEmpty()) {
                notFound.add(token);
                continue;
            }
            TradeStockBasic info = infoOpt.get();
            List<TradeStockFinancial> allRecords = financialRepository
                    .findByStockCodeOrderByReportDateDesc(info.getStockCode());
            // 建立日期索引，用于计算同比
            Map<java.time.LocalDate, TradeStockFinancial> dateMap = allRecords.stream()
                    .collect(Collectors.toMap(TradeStockFinancial::getReportDate, r -> r, (a, b) -> a));
            List<TradeStockFinancial> records = allRecords.stream().limit(limit).collect(Collectors.toList());

            // 转为升序，方便识别转折点
            List<TradeStockFinancial> asc = new ArrayList<>(records);
            java.util.Collections.reverse(asc);

            List<ProsperityQuarterDTO> quarterDTOs = buildQuarterDTOs(asc, dateMap);
            String latestLevel = quarterDTOs.isEmpty() ? "unknown"
                    : quarterDTOs.get(quarterDTOs.size() - 1).getRevenueLevel();

            stocks.add(ProsperityStockDTO.builder()
                    .stockCode(info.getStockCode())
                    .stockName(info.getStockName())
                    .latestLevel(latestLevel)
                    .quarters(quarterDTOs)
                    .build());

            for (ProsperityQuarterDTO q : quarterDTOs) {
                allQuarterDates.put(q.getReportDate(), q.getQuarter());
            }
        }

        List<String> quarterAxis = allQuarterDates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .distinct()
                .collect(Collectors.toList());

        String sectorLevel = calcSectorLevel(stocks);
        String sectorSummary = buildSectorSummary(sectorLevel);

        return ProsperityResultDTO.builder()
                .requested(tokens.size())
                .matched(stocks.size())
                .notFound(notFound)
                .sectorLevel(sectorLevel)
                .sectorSummary(sectorSummary)
                .quarterAxis(quarterAxis)
                .stocks(stocks)
                .build();
    }

    private List<ProsperityQuarterDTO> buildQuarterDTOs(List<TradeStockFinancial> ascRecords,
                                                          Map<java.time.LocalDate, TradeStockFinancial> dateMap) {
        List<ProsperityQuarterDTO> result = new ArrayList<>();
        for (int i = 0; i < ascRecords.size(); i++) {
            TradeStockFinancial f = ascRecords.get(i);
            java.time.LocalDate prevYear = f.getReportDate().minusYears(1);
            TradeStockFinancial prev = dateMap.get(prevYear);
            BigDecimal ry = f.getRevenueYoy() != null ? f.getRevenueYoy()
                    : calcYoy(f.getRevenue(), prev != null ? prev.getRevenue() : null);
            BigDecimal py = f.getDeductedNetProfitYoy() != null ? f.getDeductedNetProfitYoy()
                    : calcYoy(f.getNetProfit(), prev != null ? prev.getNetProfit() : null);

            boolean revTurn = false;
            boolean profTurn = false;
            if (i > 0) {
                BigDecimal prevRy = ascRecords.get(i - 1).getRevenueYoy();
                BigDecimal prevPy = ascRecords.get(i - 1).getDeductedNetProfitYoy();
                revTurn = prevRy != null && ry != null
                        && prevRy.compareTo(BigDecimal.ZERO) < 0
                        && ry.compareTo(BigDecimal.ZERO) > 0;
                profTurn = prevPy != null && py != null
                        && prevPy.compareTo(BigDecimal.ZERO) < 0
                        && py.compareTo(BigDecimal.ZERO) > 0;
            }

            result.add(ProsperityQuarterDTO.builder()
                    .quarter(formatQuarter(f.getReportDate()))
                    .reportDate(f.getReportDate().toString())
                    .revenueYoy(ry)
                    .deductedNetProfitYoy(py)
                    .revenueLevel(prosperityLevel(ry))
                    .profitLevel(prosperityLevel(py))
                    .revenueTurnaround(revTurn)
                    .profitTurnaround(profTurn)
                    .build());
        }
        return result;
    }

    private BigDecimal calcYoy(BigDecimal current, BigDecimal prev) {
        if (current == null || prev == null || prev.compareTo(BigDecimal.ZERO) == 0) return null;
        return current.subtract(prev)
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

    private String calcSectorLevel(List<ProsperityStockDTO> stocks) {
        if (stocks.isEmpty()) return "UNKNOWN";
        // 高景气：最新季度营收同比 ≥ 20%（约 4倍 GDP），对应 "high" 等级（≥30%）或 medium 中 ≥ 20% 的部分
        // 这里简化：只有 "high"（≥30%）才算高景气板块计数
        long highCount = stocks.stream()
                .filter(s -> "high".equals(s.getLatestLevel()))
                .count();
        long lowCount = stocks.stream()
                .filter(s -> "low".equals(s.getLatestLevel()) || "weak".equals(s.getLatestLevel()))
                .count();
        double total = stocks.size();
        if (highCount / total >= 0.6) return "HIGH";
        if (lowCount / total >= 0.6) return "LOW";
        return "MIXED";
    }

    private String buildSectorSummary(String sectorLevel) {
        return switch (sectorLevel) {
            case "HIGH" -> "高景气板块 ✓ 多数公司营收高速增长，顺势布局";
            case "LOW" -> "低景气板块，谨慎 — 行业整体增速偏低";
            default -> "景气分化，关注龙头 — 建议聚焦营收持续高增长的公司";
        };
    }

    private String formatQuarter(LocalDate d) {
        int year = d.getYear() % 100;
        int q = switch (d.getMonthValue()) {
            case 3 -> 1;
            case 6 -> 2;
            case 9 -> 3;
            case 12 -> 4;
            default -> (d.getMonthValue() - 1) / 3 + 1;
        };
        return String.format("%02dQ%d", year, q);
    }

    private List<String> parseKeywords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,，;； \t]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
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
            return Optional.of(syntheticBasic(first.getStockCode(),
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

    @Transactional(readOnly = true)
    public List<PoolItemDTO> listPool() {
        List<InvestStockPool> items = poolRepository.findAllByOrderByCreatedAtDesc();
        if (items.isEmpty()) return List.of();

        List<String> codes = items.stream().map(InvestStockPool::getStockCode).collect(Collectors.toList());

        Map<String, TradeStockBasic> basicMap = stockBasicRepository.findByStockCodeIn(codes).stream()
                .collect(Collectors.toMap(TradeStockBasic::getStockCode, b -> b, (a, b) -> a));

        Map<String, TradeStockFinancial> finMap = financialRepository.findLatestByStockCodes(codes).stream()
                .collect(Collectors.toMap(TradeStockFinancial::getStockCode, f -> f));

        Map<String, TradeStockDaily> latestDailyMap = dailyRepository.findLatestByStockCodes(codes).stream()
                .collect(Collectors.toMap(TradeStockDaily::getStockCode, d -> d, (a, b) -> a));

        LocalDate yearStart = LocalDate.of(LocalDate.now().getYear(), 1, 1);
        Map<String, TradeStockDaily> yearStartDailyMap = dailyRepository
                .findFirstAfterDateByStockCodes(codes, yearStart).stream()
                .collect(Collectors.toMap(TradeStockDaily::getStockCode, d -> d, (a, b) -> a));

        PoolPriceContext ctx = new PoolPriceContext(basicMap, finMap, latestDailyMap, yearStartDailyMap);
        return items.stream().map(p -> toPoolItemDTO(p, ctx)).collect(Collectors.toList());
    }

    private record PoolPriceContext(Map<String, TradeStockBasic> basicMap,
                                    Map<String, TradeStockFinancial> finMap,
                                    Map<String, TradeStockDaily> latestDailyMap,
                                    Map<String, TradeStockDaily> yearStartDailyMap) { }

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
        pool.setPoolType(req.getPoolType() != null ? req.getPoolType() : "quality");
        pool.setStatus(req.getStatus() != null ? req.getStatus() : "watching");
        applyPoolFields(pool, req);

        InvestStockPool saved = poolRepository.save(pool);
        return toPoolItemDTO(saved);
    }

    @Transactional
    public PoolItemDTO updatePool(Integer id, PoolSaveRequest req) {
        InvestStockPool pool = poolRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("股票池条目不存在：" + id));
        if (req.getPoolType() != null) pool.setPoolType(req.getPoolType());
        if (req.getStatus() != null) pool.setStatus(req.getStatus());
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
        if (req.getTargetSellPrice() != null) pool.setTargetSellPrice(req.getTargetSellPrice());
        if (req.getRevenueForecastY0() != null) pool.setRevenueForecastY0(req.getRevenueForecastY0());
        if (req.getRevenueForecastY1() != null) pool.setRevenueForecastY1(req.getRevenueForecastY1());
        if (req.getRevenueForecastY2() != null) pool.setRevenueForecastY2(req.getRevenueForecastY2());
    }

    /**
     * 单字段更新（内联编辑）。空字符串视为清空（设为 null），允许撤销字段值。
     */
    @Transactional
    public PoolItemDTO updateField(Integer id, PoolFieldUpdateRequest req) {
        if (req == null || req.getField() == null || req.getField().isBlank()) {
            throw new IllegalArgumentException("字段名不能为空");
        }
        InvestStockPool pool = poolRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("股票池条目不存在：" + id));
        String field = req.getField().trim();
        String raw = req.getValue();
        boolean blank = raw == null || raw.isBlank();
        switch (field) {
            case "poolType" -> pool.setPoolType(blank ? "quality" : raw.trim());
            case "status" -> {
                String v = blank ? "watching" : raw.trim();
                pool.setStatus(v);
                if (!"watching".equals(pool.getAlertState()) && "exited".equals(v)) {
                    pool.setAlertState("none");
                }
            }
            case "memo" -> pool.setMemo(blank ? null : raw);
            case "undervaluedPrice" -> pool.setUndervaluedPrice(parseDecimal(raw));
            case "fairPrice" -> pool.setFairPrice(parseDecimal(raw));
            case "overvaluedPrice" -> pool.setOvervaluedPrice(parseDecimal(raw));
            case "targetBuyPrice" -> {
                pool.setTargetBuyPrice(parseDecimal(raw));
                pool.setAlertState("none");
            }
            case "targetSellPrice" -> {
                pool.setTargetSellPrice(parseDecimal(raw));
                pool.setAlertState("none");
            }
            case "revenueForecastY0" -> pool.setRevenueForecastY0(parseDecimal(raw));
            case "revenueForecastY1" -> pool.setRevenueForecastY1(parseDecimal(raw));
            case "revenueForecastY2" -> pool.setRevenueForecastY2(parseDecimal(raw));
            default -> throw new IllegalArgumentException("不支持的字段：" + field);
        }
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

    @Transactional
    public void removeFromPool(Integer id) {
        poolRepository.deleteById(id);
    }

    /** 单条转换，供 addToPool / updatePool / updateField 使用。 */
    private PoolItemDTO toPoolItemDTO(InvestStockPool pool) {
        String code = pool.getStockCode();
        TradeStockBasic basic = stockBasicRepository.findByStockCode(code).orElse(null);
        TradeStockFinancial fin = financialRepository
                .findByStockCodeOrderByReportDateDesc(code)
                .stream().findFirst().orElse(null);
        TradeStockDaily latestDaily = dailyRepository
                .findFirstByStockCodeOrderByTradeDateDesc(code).orElse(null);
        TradeStockDaily yearStartDaily = dailyRepository
                .findFirstByStockCodeAndTradeDateGreaterThanEqualOrderByTradeDateAsc(
                        code, LocalDate.of(LocalDate.now().getYear(), 1, 1))
                .orElse(null);

        Map<String, TradeStockBasic> basicMap = basic != null ? Map.of(code, basic) : Map.of();
        Map<String, TradeStockFinancial> finMap = fin != null ? Map.of(code, fin) : Map.of();
        Map<String, TradeStockDaily> latestDailyMap = latestDaily != null ? Map.of(code, latestDaily) : Map.of();
        Map<String, TradeStockDaily> yearStartDailyMap = yearStartDaily != null ? Map.of(code, yearStartDaily) : Map.of();
        return toPoolItemDTO(pool, new PoolPriceContext(basicMap, finMap, latestDailyMap, yearStartDailyMap));
    }

    private PoolItemDTO toPoolItemDTO(InvestStockPool pool, PoolPriceContext ctx) {
        String code = pool.getStockCode();
        TradeStockBasic basic = ctx.basicMap().get(code);
        String stockName = basic != null ? basic.getStockName() : code;
        TradeStockFinancial fin = ctx.finMap().get(code);
        TradeStockDaily latest = ctx.latestDailyMap().get(code);
        TradeStockDaily yearStart = ctx.yearStartDailyMap().get(code);

        BigDecimal latestPrice = latest != null ? latest.getClosePrice() : null;
        BigDecimal ytdGain = computeYtdGain(latest, yearStart);
        BigDecimal marketCap = computeMarketCap(latestPrice, basic);

        BigDecimal latestRevenueYoy = fin != null ? fin.getRevenueYoy() : null;
        BigDecimal latestProfitYoy = fin != null ? fin.getDeductedNetProfitYoy() : null;
        String latestLevel = prosperityLevel(latestRevenueYoy);

        return PoolItemDTO.builder()
                .id(pool.getId())
                .stockCode(code)
                .stockName(stockName)
                .poolType(pool.getPoolType())
                .poolTypeLabel("quality".equals(pool.getPoolType()) ? "质量优选" : "科技风投")
                .memo(pool.getMemo())
                .undervaluedPrice(pool.getUndervaluedPrice())
                .fairPrice(pool.getFairPrice())
                .overvaluedPrice(pool.getOvervaluedPrice())
                .targetBuyPrice(pool.getTargetBuyPrice())
                .targetSellPrice(pool.getTargetSellPrice())
                .targetPrice(pool.getTargetPrice())
                .revenueForecastY0(pool.getRevenueForecastY0())
                .revenueForecastY1(pool.getRevenueForecastY1())
                .revenueForecastY2(pool.getRevenueForecastY2())
                .status(pool.getStatus())
                .statusLabel(statusLabel(pool.getStatus()))
                .alertState(pool.getAlertState())
                .lastAlertAt(pool.getLastAlertAt())
                .latestPrice(latestPrice)
                .ytdGain(ytdGain)
                .marketCap(marketCap)
                .latestRevenueYoy(latestRevenueYoy)
                .latestProfitYoy(latestProfitYoy)
                .latestLevel(latestLevel)
                .createdAt(pool.getCreatedAt())
                .updatedAt(pool.getUpdatedAt())
                .build();
    }

    private BigDecimal computeYtdGain(TradeStockDaily latest, TradeStockDaily yearStart) {
        if (latest == null || yearStart == null) return null;
        BigDecimal close = latest.getClosePrice();
        BigDecimal base = yearStart.getClosePrice();
        if (close == null || base == null || base.compareTo(BigDecimal.ZERO) == 0) return null;
        return close.subtract(base)
                .divide(base, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeMarketCap(BigDecimal latestPrice, TradeStockBasic basic) {
        if (latestPrice == null || basic == null || basic.getTotalShares() == null) return null;
        BigDecimal totalShares = BigDecimal.valueOf(basic.getTotalShares());
        BigDecimal totalCap = totalShares.multiply(latestPrice);
        return totalCap.divide(BigDecimal.valueOf(100_000_000L), 2, RoundingMode.HALF_UP);
    }

    private String statusLabel(String status) {
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
        List<TradeStockFinancial> all = financialRepository
                .findByStockCodeOrderByReportDateDesc(info.getStockCode());
        if (all.isEmpty()) {
            return SopCheckupDTO.builder()
                    .matched(false)
                    .stockCode(info.getStockCode())
                    .stockName(info.getStockName())
                    .message("暂无该股票的财务数据")
                    .build();
        }
        Map<LocalDate, TradeStockFinancial> dateMap = all.stream()
                .collect(Collectors.toMap(TradeStockFinancial::getReportDate, r -> r, (a, b) -> a));
        List<TradeStockFinancial> asc = new ArrayList<>(
                all.stream().limit(SOP_QUARTERS).collect(Collectors.toList()));
        java.util.Collections.reverse(asc);

        SopCheckupDTO.MetricCheck gross = buildGrossMargin(asc);
        SopCheckupDTO.MetricCheck rev = buildRevenueYoy(asc, dateMap);
        SopCheckupDTO.MetricCheck profit = buildProfitYoy(asc, dateMap, rev.getLatest());

        String overall = combineVerdict(gross.getVerdict(), rev.getVerdict(), profit.getVerdict());
        String summary = switch (overall) {
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
            series.add(SopCheckupDTO.QuarterPoint.builder()
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
                tip = String.format("毛利率从 %.1f%% 提升到 %.1f%%，定价权强", first.doubleValue(), latest.doubleValue());
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
                .label("毛利率").unit("%").series(series).latest(latest).verdict(verdict).tip(tip).build();
    }

    private SopCheckupDTO.MetricCheck buildRevenueYoy(List<TradeStockFinancial> asc,
                                                       Map<LocalDate, TradeStockFinancial> dateMap) {
        List<SopCheckupDTO.QuarterPoint> series = new ArrayList<>();
        BigDecimal latest = null;
        int highCount = 0, valid = 0;
        for (TradeStockFinancial f : asc) {
            BigDecimal yoy = f.getRevenueYoy();
            if (yoy == null) {
                TradeStockFinancial prev = dateMap.get(f.getReportDate().minusYears(1));
                yoy = calcYoy(f.getRevenue(), prev != null ? prev.getRevenue() : null);
            }
            series.add(SopCheckupDTO.QuarterPoint.builder()
                    .quarter(formatQuarter(f.getReportDate())).value(yoy).build());
            if (yoy != null) {
                valid++;
                latest = yoy;
                if (yoy.doubleValue() >= 20) highCount++;
            }
        }
        String verdict, tip;
        if (valid == 0 || latest == null) {
            verdict = "warn"; tip = "缺少营收同比数据";
        } else {
            double ratio = highCount * 1.0 / valid;
            if (latest.doubleValue() >= 20 && ratio >= 0.6) {
                verdict = "pass";
                tip = String.format("最新营收 +%.1f%%，%d/%d 个季度 ≥ 20%%，持续高增长",
                        latest.doubleValue(), highCount, valid);
            } else if (latest.doubleValue() >= 10) {
                verdict = "warn";
                tip = String.format("最新营收 +%.1f%%，未达 20%% 高增长线", latest.doubleValue());
            } else {
                verdict = "fail";
                tip = String.format("最新营收 %.1f%%，增长乏力", latest.doubleValue());
            }
        }
        return SopCheckupDTO.MetricCheck.builder()
                .label("营收同比").unit("%").series(series).latest(latest).verdict(verdict).tip(tip).build();
    }

    private SopCheckupDTO.MetricCheck buildProfitYoy(List<TradeStockFinancial> asc,
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
            series.add(SopCheckupDTO.QuarterPoint.builder()
                    .quarter(formatQuarter(f.getReportDate())).value(py).build());
            if (py != null) latest = py;
        }
        String verdict, tip;
        if (latest == null) {
            verdict = "warn"; tip = "缺少扣非净利润同比数据";
        } else if (latestRevenueYoy == null) {
            verdict = latest.doubleValue() >= 20 ? "pass" : (latest.doubleValue() >= 0 ? "warn" : "fail");
            tip = String.format("最新扣非 %+.1f%%", latest.doubleValue());
        } else {
            double diff = latest.doubleValue() - latestRevenueYoy.doubleValue();
            if (diff >= 5 && latest.doubleValue() >= 0) {
                verdict = "pass";
                tip = String.format("扣非 +%.1f%% > 营收 +%.1f%%，规模效应显著",
                        latest.doubleValue(), latestRevenueYoy.doubleValue());
            } else if (latest.doubleValue() >= 0 && diff >= -5) {
                verdict = "warn";
                tip = String.format("扣非 %+.1f%% 与营收 %+.1f%% 基本同步，盈利能力未提升",
                        latest.doubleValue(), latestRevenueYoy.doubleValue());
            } else {
                verdict = "fail";
                tip = String.format("扣非 %+.1f%% 落后营收 %+.1f%%，规模不经济",
                        latest.doubleValue(), latestRevenueYoy.doubleValue());
            }
        }
        return SopCheckupDTO.MetricCheck.builder()
                .label("扣非净利润同比").unit("%").series(series).latest(latest).verdict(verdict).tip(tip).build();
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
