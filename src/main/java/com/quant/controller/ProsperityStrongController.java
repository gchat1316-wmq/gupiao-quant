package com.quant.controller;

import com.quant.dto.prosperitystrong.HotSectorDTO;
import com.quant.dto.prosperitystrong.LeaderCandidateDTO;
import com.quant.dto.prosperitystrong.PickDailyDTO;
import com.quant.dto.prosperitystrong.PipelineRunResultDTO;
import com.quant.entity.InvestStockPool;
import com.quant.entity.ProsperityPickDaily;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.ProsperityPickDailyRepository;
import com.quant.service.prosperitystrong.ProsperityStrongPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/prosperity-strong")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ProsperityStrongController {

    private final ProsperityStrongPipelineService pipeline;
    private final ProsperityPickDailyRepository pickRepo;
    private final InvestStockPoolRepository poolRepo;

    /** 手动触发流水线(同步,可能耗时几十秒) */
    @PostMapping("/run")
    public PipelineRunResultDTO run(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return pipeline.run(date == null ? LocalDate.now() : date);
    }

    /** 当日热门板块 */
    @GetMapping("/sectors")
    public List<HotSectorDTO> sectors(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return pipeline.sectors(date);
    }

    /** 当日龙头候选(Step2 中间结果,调试用) */
    @GetMapping("/leaders")
    public List<LeaderCandidateDTO> leaders(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return pipeline.leadersByDate(date);
    }

    /** 当日最终候选 + 仓位建议 */
    @GetMapping("/candidates")
    public List<PickDailyDTO> candidates(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return pipeline.candidates(date);
    }

    /** 单股深度报告(含仓位决策卡) */
    @GetMapping("/detail/{stockCode}")
    public PickDailyDTO detail(
            @PathVariable String stockCode,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return pipeline.detail(stockCode, date);
    }

    /** 历史回顾 */
    @GetMapping("/history")
    public List<PickDailyDTO> history(
            @RequestParam(value = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pipeline.history(from, to);
    }

    /** 系统状态: 最近一次运行的日期 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        LocalDate latest = pipeline.latestSnapDate();
        return Map.of(
                "latestSnapDate", latest == null ? "" : latest.toString(),
                "now", LocalDate.now().toString()
        );
    }

    /** 一键加入龙江投资股票池(写入估值价 + 目标买卖价 + 备注) */
    @PostMapping("/promote/{stockCode}")
    public Map<String, Object> promote(
            @PathVariable String stockCode,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date == null ? pipeline.latestSnapDate() : date;
        if (d == null) throw new IllegalArgumentException("无可用快照日期");
        ProsperityPickDaily pick = pickRepo.findBySnapDateAndStockCode(d, stockCode)
                .orElseThrow(() -> new IllegalArgumentException("未找到候选: " + stockCode));

        InvestStockPool pool = poolRepo.findByStockCode(stockCode).orElseGet(InvestStockPool::new);
        boolean isNew = pool.getId() == null;
        pool.setStockCode(stockCode);
        pool.setStockName(pick.getStockName());
        if (pool.getPoolType() == null || pool.getPoolType().isBlank()) {
            pool.setPoolType("tech_vc");
        }
        if (pool.getStatus() == null || pool.getStatus().isBlank()) {
            pool.setStatus("watching");
        }
        pool.setUndervaluedPrice(pick.getPriceLow());
        pool.setFairPrice(pick.getPriceMid());
        pool.setOvervaluedPrice(pick.getPriceHigh());
        pool.setTargetBuyPrice(pick.getBuyLeftPrice());
        pool.setTargetSellPrice(pick.getSellTarget1());

        String memo = String.format(
                "[强势股推荐 %s] 板块=%s 综合分=%s 净利率=%s%% 建仓<=¥%s 目标=¥%s 止损=¥%s 仓位=%s/%s%%",
                pick.getSnapDate(),
                pick.getSectorName(),
                pick.getCombinedScore(),
                pick.getNetMarginAvg4q(),
                pick.getBuyLeftPrice(),
                pick.getSellTarget1(),
                pick.getStopLossPrice(),
                pick.getCorePositionPct(),
                pick.getTacticalPositionPct()
        );
        if (pool.getMemo() == null || pool.getMemo().isBlank()) {
            pool.setMemo(memo);
        } else {
            pool.setMemo(pool.getMemo() + "\n" + memo);
        }
        poolRepo.save(pool);
        return Map.of(
                "message", isNew ? "已加入股票池" : "已更新股票池条目",
                "stockCode", stockCode,
                "snapDate", d.toString()
        );
    }
}
