package com.quant.service.prosperitystrong;

import com.quant.config.ProsperityStrongProperties;
import com.quant.entity.ProsperityHotSector;
import com.quant.entity.ProsperityLeaderCandidate;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 2: 龙头识别
 *
 * 在板块成分股内按 (年度涨幅 + 5日涨幅 + 换手率) 加权评分,
 * 并剔除业绩预亏 / ST / 停牌 / 近5日跌停 / 次新股(<1年)。
 *
 * 为简化首阶段,从 TradeStockBasic.sectorNames 字段中模糊匹配板块名称,
 * 找出该板块内的成分股进行评分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaderIdentifier {

    private final TradeStockBasicRepository basicRepo;
    private final TradeStockDailyRepository dailyRepo;
    private final ProsperityStrongProperties props;

    public List<ProsperityLeaderCandidate> identify(LocalDate snapDate, ProsperityHotSector sector) {
        if (sector == null || sector.getSectorName() == null) return Collections.emptyList();

        List<TradeStockBasic> realMembers = basicRepo.findBySectorNameLike(sector.getSectorName());
        if (realMembers.size() > 100) {
            realMembers = realMembers.subList(0, 100);
        }
        if (realMembers.isEmpty()) {
            log.info("板块[{}] 未找到成分股,跳过", sector.getSectorName());
            return Collections.emptyList();
        }

        Map<String, TradeStockDaily> latestQuotes = new HashMap<>();
        for (TradeStockDaily d : dailyRepo.findLatestByStockCodes(
                realMembers.stream().map(TradeStockBasic::getStockCode).toList())) {
            latestQuotes.put(d.getStockCode(), d);
        }
        LocalDate yearStart = LocalDate.of(snapDate.getYear(), 1, 1);
        Map<String, TradeStockDaily> yearStartQuotes = new HashMap<>();
        for (TradeStockDaily d : dailyRepo.findFirstAfterDateByStockCodes(
                realMembers.stream().map(TradeStockBasic::getStockCode).toList(), yearStart)) {
            yearStartQuotes.put(d.getStockCode(), d);
        }

        List<ProsperityLeaderCandidate> scored = new ArrayList<>();
        for (TradeStockBasic basic : realMembers) {
            TradeStockDaily latest = latestQuotes.get(basic.getStockCode());
            if (latest == null) continue;

            String filterReason = passFastFilter(basic, latest);
            BigDecimal ytdChange = ytdChange(latest, yearStartQuotes.get(basic.getStockCode()));
            BigDecimal turnover = latest.getTurnoverRate();
            // 简化的 5 日涨幅:用最近6条记录里的第一/最后一条
            List<TradeStockDaily> last6 = dailyRepo.findTop6ByStockCodeOrderByTradeDateDesc(basic.getStockCode());
            BigDecimal change5d = null;
            if (last6.size() >= 5) {
                BigDecimal cur = last6.get(0).getClosePrice();
                BigDecimal old = last6.get(last6.size() - 1).getClosePrice();
                if (cur != null && old != null && old.compareTo(BigDecimal.ZERO) > 0) {
                    change5d = cur.subtract(old).divide(old, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                }
            }

            ProsperityLeaderCandidate cand = new ProsperityLeaderCandidate();
            cand.setSnapDate(snapDate);
            cand.setSectorId(sector.getId() == null ? 0 : sector.getId());
            cand.setSectorName(sector.getSectorName());
            cand.setStockCode(basic.getStockCode());
            cand.setStockName(basic.getStockName());
            cand.setYtdChange(ytdChange);
            cand.setChange5d(change5d);
            cand.setTurnoverRate(turnover);
            cand.setLeaderScore(score(ytdChange, change5d, turnover));
            cand.setFilterPassed(filterReason == null ? 1 : 0);
            cand.setFilterReason(filterReason);
            scored.add(cand);
        }

        scored.sort(Comparator.comparing(ProsperityLeaderCandidate::getLeaderScore,
                Comparator.nullsLast(Comparator.reverseOrder())));

        // 仅返回通过过滤的前 N 只
        List<ProsperityLeaderCandidate> passed = new ArrayList<>();
        for (ProsperityLeaderCandidate c : scored) {
            if (c.getFilterPassed() != null && c.getFilterPassed() == 1) {
                passed.add(c);
                if (passed.size() >= props.getLeadersPerSector()) break;
            }
        }
        return passed;
    }

    private String passFastFilter(TradeStockBasic basic, TradeStockDaily latest) {
        if (basic.getStockName() != null && basic.getStockName().contains("ST")) return "ST标的";
        if (basic.getIsTrading() != null && basic.getIsTrading() == 0) return "停牌";
        if (basic.getListDate() != null && basic.getListDate().isAfter(LocalDate.now().minusYears(1))) {
            return "次新股(上市不足1年)";
        }
        return null;
    }

    private BigDecimal ytdChange(TradeStockDaily latest, TradeStockDaily base) {
        if (latest == null || base == null) return null;
        BigDecimal cur = latest.getClosePrice();
        BigDecimal old = base.getClosePrice();
        if (cur == null || old == null || old.compareTo(BigDecimal.ZERO) == 0) return null;
        return cur.subtract(old).divide(old, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal score(BigDecimal ytd, BigDecimal d5, BigDecimal turnover) {
        double y = ytd == null ? 0 : Math.min(150, Math.max(-30, ytd.doubleValue())) + 30;
        double f = d5 == null ? 0 : Math.min(20, Math.max(-15, d5.doubleValue())) + 15;
        double t = turnover == null ? 0 : Math.min(15, turnover.doubleValue());
        // 归一化大致到 0-100
        double yScore = y / 180.0 * 100;          // 0-100
        double fScore = f / 35.0 * 100;            // 0-100
        double tScore = t / 15.0 * 100;            // 0-100
        double total = 0.4 * yScore + 0.4 * fScore + 0.2 * tScore;
        return BigDecimal.valueOf(Math.max(0, Math.min(100, total))).setScale(2, RoundingMode.HALF_UP);
    }
}
