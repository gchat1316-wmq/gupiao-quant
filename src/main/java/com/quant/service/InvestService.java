package com.quant.service;

import com.quant.dto.invest.PoolItemDTO;
import com.quant.dto.invest.PoolSaveRequest;
import com.quant.dto.invest.ProsperityQuarterDTO;
import com.quant.dto.invest.ProsperityResultDTO;
import com.quant.dto.invest.ProsperityStockDTO;
import com.quant.entity.InvestStockPool;
import com.quant.entity.TradeStockFinancial;
import com.quant.entity.TradeStockInfo;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.repository.TradeStockInfoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class InvestService {

    private static final int DEFAULT_QUARTERS = 8;
    private static final int MAX_QUARTERS = 16;

    private final TradeStockInfoRepository stockInfoRepository;
    private final TradeStockFinancialRepository financialRepository;
    private final InvestStockPoolRepository poolRepository;

    public InvestService(TradeStockInfoRepository stockInfoRepository,
                         TradeStockFinancialRepository financialRepository,
                         InvestStockPoolRepository poolRepository) {
        this.stockInfoRepository = stockInfoRepository;
        this.financialRepository = financialRepository;
        this.poolRepository = poolRepository;
    }

    // ===== 景气度扫描 =====

    @Transactional(readOnly = true)
    public ProsperityResultDTO queryProsperity(String keywords, Integer quarters) {
        int limit = (quarters == null || quarters <= 0) ? DEFAULT_QUARTERS : Math.min(quarters, MAX_QUARTERS);
        List<String> tokens = parseKeywords(keywords);

        List<ProsperityStockDTO> stocks = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        Map<String, String> allQuarterDates = new LinkedHashMap<>();

        for (String token : tokens) {
            Optional<TradeStockInfo> infoOpt = resolveStock(token);
            if (infoOpt.isEmpty()) {
                notFound.add(token);
                continue;
            }
            TradeStockInfo info = infoOpt.get();
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

    private Optional<TradeStockInfo> resolveStock(String token) {
        String t = token.trim();
        if (t.isEmpty()) return Optional.empty();

        // 兼容带交易所后缀的代码，如 600519.SH → bareCode=600519
        String bareCode = t.contains(".") ? t.substring(0, t.indexOf('.')) : t;

        if (bareCode.matches("\\d{4,8}")) {
            // 先用裸代码查 stock_info
            Optional<TradeStockInfo> byCode = stockInfoRepository.findByStockCode(bareCode);
            if (byCode.isPresent()) return byCode;
            // fallback：查财务数据（支持 600519 和 600519.SH 两种格式）
            List<TradeStockFinancial> fin = financialRepository.findByStockCodeOrderByReportDateDesc(bareCode);
            if (!fin.isEmpty()) return Optional.of(syntheticInfo(bareCode, bareCode));
        }
        List<TradeStockInfo> byName = stockInfoRepository.findByStockNameLike(t);
        if (!byName.isEmpty()) return Optional.of(byName.get(0));
        return Optional.empty();
    }

    private TradeStockInfo syntheticInfo(String code, String name) {
        TradeStockInfo info = new TradeStockInfo();
        info.setStockCode(code);
        info.setStockName(name);
        return info;
    }

    // ===== 股票池管理 =====

    @Transactional(readOnly = true)
    public List<PoolItemDTO> listPool() {
        List<InvestStockPool> items = poolRepository.findAllByOrderByCreatedAtDesc();
        return items.stream().map(this::toPoolItemDTO).collect(Collectors.toList());
    }

    @Transactional
    public PoolItemDTO addToPool(PoolSaveRequest req) {
        String kw = req.getKeyword() == null ? "" : req.getKeyword().trim();
        Optional<TradeStockInfo> infoOpt = resolveStock(kw);
        // 最后兜底：纯数字代码格式直接放行（财务数据将来会有）
        if (infoOpt.isEmpty() && kw.matches("\\d{4,8}")) {
            infoOpt = Optional.of(syntheticInfo(kw, kw));
        }
        if (infoOpt.isEmpty()) {
            throw new IllegalArgumentException("未找到股票：" + kw + "（请输入6位股票代码或完整名称）");
        }
        TradeStockInfo info = infoOpt.get();
        if (poolRepository.findByStockCode(info.getStockCode()).isPresent()) {
            throw new IllegalArgumentException("该股票已在股票池中：" + info.getStockName());
        }

        InvestStockPool pool = new InvestStockPool();
        pool.setStockCode(info.getStockCode());
        pool.setPoolType(req.getPoolType() != null ? req.getPoolType() : "quality");
        pool.setMemo(req.getMemo());
        pool.setTargetPrice(req.getTargetPrice());
        pool.setStatus(req.getStatus() != null ? req.getStatus() : "watching");

        InvestStockPool saved = poolRepository.save(pool);
        return toPoolItemDTO(saved);
    }

    @Transactional
    public PoolItemDTO updatePool(Integer id, PoolSaveRequest req) {
        InvestStockPool pool = poolRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("股票池条目不存在：" + id));
        if (req.getPoolType() != null) pool.setPoolType(req.getPoolType());
        if (req.getMemo() != null) pool.setMemo(req.getMemo());
        if (req.getTargetPrice() != null) pool.setTargetPrice(req.getTargetPrice());
        if (req.getStatus() != null) pool.setStatus(req.getStatus());
        return toPoolItemDTO(poolRepository.save(pool));
    }

    @Transactional
    public void removeFromPool(Integer id) {
        poolRepository.deleteById(id);
    }

    private PoolItemDTO toPoolItemDTO(InvestStockPool pool) {
        String stockName = stockInfoRepository.findByStockCode(pool.getStockCode())
                .map(TradeStockInfo::getStockName)
                .orElse(pool.getStockCode());

        // 查最新季度财务数据
        List<TradeStockFinancial> recent = financialRepository
                .findByStockCodeOrderByReportDateDesc(pool.getStockCode())
                .stream().limit(1).collect(Collectors.toList());

        BigDecimal latestRevenueYoy = null;
        BigDecimal latestProfitYoy = null;
        String latestLevel = "unknown";
        if (!recent.isEmpty()) {
            latestRevenueYoy = recent.get(0).getRevenueYoy();
            latestProfitYoy = recent.get(0).getDeductedNetProfitYoy();
            latestLevel = prosperityLevel(latestRevenueYoy);
        }

        return PoolItemDTO.builder()
                .id(pool.getId())
                .stockCode(pool.getStockCode())
                .stockName(stockName)
                .poolType(pool.getPoolType())
                .poolTypeLabel("quality".equals(pool.getPoolType()) ? "质量优选" : "科技风投")
                .memo(pool.getMemo())
                .targetPrice(pool.getTargetPrice())
                .status(pool.getStatus())
                .statusLabel(statusLabel(pool.getStatus()))
                .latestRevenueYoy(latestRevenueYoy)
                .latestProfitYoy(latestProfitYoy)
                .latestLevel(latestLevel)
                .createdAt(pool.getCreatedAt())
                .updatedAt(pool.getUpdatedAt())
                .build();
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "holding" -> "持仓中";
            case "exited" -> "已离场";
            default -> "观察中";
        };
    }
}
