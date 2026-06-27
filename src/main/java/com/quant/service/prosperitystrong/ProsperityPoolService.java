package com.quant.service.prosperitystrong;

import com.quant.entity.ProsperityPickDaily;
import com.quant.entity.ProsperityStockPool;
import com.quant.repository.ProsperityPickDailyRepository;
import com.quant.repository.ProsperityStockPoolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 热点股票池服务 — 龙头候选"入池"动作的落地。
 *
 * <p>与 {@code InvestService.addToPool}（龙江投资股票池）独立：
 * 本服务只服务于"热点选股"模块的短线/波段池子，pool_type 语义、字段模型都不同。
 *
 * <p>幂等性：同股票重复入池 = 累加 {@code poolCount} + 更新 {@code last_added_at} /
 * {@code last_snap_date} + 在 {@code memo} 末尾追加新推荐理由。绝不抛"已存在"错误，
 * 因为流水线按日跑，重复触发是常态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProsperityPoolService {

    private final ProsperityStockPoolRepository poolRepo;
    private final ProsperityPickDailyRepository pickRepo;

    /**
     * 将 {@code stockCode} 在 {@code snapDate} 快照下的候选推荐入到个人热点股票池。
     *
     * @param ownerId 所有者 user_id，NULL 表示系统共享（兼容历史数据）
     * @return 写入结果摘要 (message / stockCode / snapDate / isNew)
     */
    @Transactional
    public Map<String, Object> promote(String stockCode, LocalDate snapDate, Long ownerId) {
        if (snapDate == null) {
            snapDate = pickRepo.findFirstByOrderBySnapDateDesc()
                    .map(ProsperityPickDaily::getSnapDate)
                    .orElseThrow(() -> new IllegalArgumentException("无可用快照日期"));
        }
        ProsperityPickDaily pick = pickRepo.findBySnapDateAndStockCode(snapDate, stockCode)
                .orElseThrow(() -> new IllegalArgumentException("未找到候选: " + stockCode));

        // 个人池查 owner_id，共享池查 NULL
        var poolOpt = ownerId == null
                ? poolRepo.findByOwnerIdIsNullAndStockCode(stockCode)
                : poolRepo.findByOwnerIdAndStockCode(ownerId, stockCode);
        ProsperityStockPool pool = poolOpt.orElseGet(ProsperityStockPool::new);
        boolean isNew = pool.getId() == null;

        if (isNew) {
            pool.setOwnerId(ownerId);
            pool.setStockCode(stockCode);
            pool.setStockName(pick.getStockName());
            pool.setStatus("watching");
            pool.setPoolCount(1);
        } else {
            pool.setPoolCount((pool.getPoolCount() == null ? 0 : pool.getPoolCount()) + 1);
        }
        // 每次入池都刷新快照
        pool.setLastAddedAt(LocalDateTime.now());
        pool.setLastSnapDate(snapDate);
        pool.setSectorName(pick.getSectorName());
        pool.setCombinedScore(pick.getCombinedScore());
        pool.setLatestPrice(pick.getLatestPrice());
        pool.setBuyLeftPrice(pick.getBuyLeftPrice());
        pool.setSellTarget1(pick.getSellTarget1());
        pool.setStopLossPrice(pick.getStopLossPrice());
        pool.setCorePositionPct(pick.getCorePositionPct());
        pool.setTacticalPositionPct(pick.getTacticalPositionPct());
        pool.setActionSignal(pick.getActionSignal());

        String memoLine = String.format(
                "[%s] 板块=%s 综合分=%s 现价=%s 建仓<=%s 目标=%s 止损=%s 仓位=%s/%s%% 信号=%s",
                snapDate,
                pick.getSectorName(),
                pick.getCombinedScore(),
                moneyOrDash(pick.getLatestPrice()),
                moneyOrDash(pick.getBuyLeftPrice()),
                moneyOrDash(pick.getSellTarget1()),
                moneyOrDash(pick.getStopLossPrice()),
                numOrDash(pick.getCorePositionPct()),
                numOrDash(pick.getTacticalPositionPct()),
                pick.getActionSignal() == null ? "--" : pick.getActionSignal());
        if (pool.getMemo() == null || pool.getMemo().isBlank()) {
            pool.setMemo(memoLine);
        } else {
            pool.setMemo(pool.getMemo() + "\n" + memoLine);
        }
        poolRepo.save(pool);

        log.info("龙头候选入池: code={} ownerId={} snapDate={} isNew={} poolCount={}",
                stockCode, ownerId, snapDate, isNew, pool.getPoolCount());

        return Map.of(
                "message", isNew ? "已加入热点股票池" : "已更新热点股票池条目",
                "stockCode", stockCode,
                "snapDate", snapDate.toString(),
                "isNew", isNew,
                "poolCount", pool.getPoolCount());
    }

    /** 无 owner 的 promote（兼容旧调用，写入系统共享池） */
    public Map<String, Object> promote(String stockCode, LocalDate snapDate) {
        return promote(stockCode, snapDate, null);
    }

    /**
     * 列出个人池（ownerId!=null），同时附加系统共享池（owner_id=NULL）。
     */
    public List<ProsperityStockPool> list(Long ownerId) {
        List<ProsperityStockPool> result = new ArrayList<>(poolRepo.findByOwnerIdOrderByLastAddedAtDesc(ownerId));
        result.addAll(poolRepo.findByOwnerIdIsNullOrderByLastAddedAtDesc());
        return result;
    }

    /** 无 owner 的 list（兼容旧调用，返回全部） */
    public List<ProsperityStockPool> list() {
        return poolRepo.findAllByOrderByLastAddedAtDesc();
    }

    private static String moneyOrDash(java.math.BigDecimal v) {
        return v == null ? "--" : "¥" + v.toPlainString();
    }

    private static String numOrDash(java.math.BigDecimal v) {
        return v == null ? "--" : v.toPlainString();
    }
}
