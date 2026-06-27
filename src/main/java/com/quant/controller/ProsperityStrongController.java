package com.quant.controller;

import com.quant.dto.prosperitystrong.HotSectorDTO;
import com.quant.dto.prosperitystrong.LeaderCandidateDTO;
import com.quant.dto.prosperitystrong.PickDailyDTO;
import com.quant.dto.prosperitystrong.PipelineRunDTO;
import com.quant.dto.prosperitystrong.PipelineRunResultDTO;
import com.quant.dto.prosperitystrong.ProsperityPoolItemDTO;
import com.quant.dto.prosperitystrong.ProviderCapabilityDTO;
import com.quant.entity.ProsperityStockPool;
import com.quant.security.UserPrincipal;
import com.quant.service.prosperitystrong.ProsperityDataProviderService;
import com.quant.service.prosperitystrong.ProsperityPoolService;
import com.quant.service.prosperitystrong.ProsperityStrongPipelineService;
import com.quant.service.prosperitystrong.WindAifinMarketClient;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final ProsperityDataProviderService providers;
    private final WindAifinMarketClient windClient;
    private final ProsperityPoolService poolService;

    /** 手动触发流水线(同步,可能耗时几十秒) */
    @PostMapping("/run")
    public ResponseEntity<PipelineRunResultDTO> run(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "provider", required = false) String provider) {
        PipelineRunResultDTO result = pipeline.run(date == null ? LocalDate.now() : date, provider);
        // 流水线忙(并发触发被锁拒绝) → 409, 让前端可以重试
        if ("BUSY".equalsIgnoreCase(result.getStatus())) {
            return ResponseEntity.status(409).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** 数据链路能力诊断 */
    @GetMapping("/providers")
    public List<ProviderCapabilityDTO> providers() {
        return providers.capabilities();
    }

    /** Wind 自然语言 A 股筛选,用于后续补数/替代本地日线缺口 */
    @GetMapping("/providers/wind/search-stocks")
    public Map<String, Object> windSearchStocks(
            @RequestParam(value = "question", defaultValue = "筛选沪深市场近5日涨幅居前且换手率较高且非ST的股票") String question,
            @RequestParam(value = "limit", defaultValue = "20") int limit) throws Exception {
        return windClient.searchStocks(question, limit);
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
                "now", LocalDate.now().toString(),
                "defaultProvider", providers.normalize(null)
        );
    }

    /** 一键加入个人热点股票池(需登录，按当前用户隔离) */
    @PostMapping("/promote/{stockCode}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> promote(
            @PathVariable String stockCode,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal UserPrincipal principal) {
        return poolService.promote(stockCode, date, principal.getId());
    }

    /** 热点股票池列表(返回当前用户的个人池+系统共享池) */
    @GetMapping("/pool")
    public List<ProsperityPoolItemDTO> pool(
            @AuthenticationPrincipal UserPrincipal principal) {
        Long ownerId = principal == null ? null : principal.getId();
        return poolService.list(ownerId).stream().map(ProsperityStrongController::toPoolDTO).toList();
    }

    private static ProsperityPoolItemDTO toPoolDTO(ProsperityStockPool e) {
        return ProsperityPoolItemDTO.builder()
                .id(e.getId())
                .stockCode(e.getStockCode())
                .stockName(e.getStockName())
                .status(e.getStatus())
                .poolCount(e.getPoolCount())
                .firstAddedAt(e.getFirstAddedAt())
                .lastAddedAt(e.getLastAddedAt())
                .lastSnapDate(e.getLastSnapDate())
                .sectorName(e.getSectorName())
                .combinedScore(e.getCombinedScore())
                .latestPrice(e.getLatestPrice())
                .buyLeftPrice(e.getBuyLeftPrice())
                .sellTarget1(e.getSellTarget1())
                .stopLossPrice(e.getStopLossPrice())
                .corePositionPct(e.getCorePositionPct())
                .tacticalPositionPct(e.getTacticalPositionPct())
                .actionSignal(e.getActionSignal())
                .memo(e.getMemo())
                .ownerId(e.getOwnerId())
                .build();
    }

    /** 流水线执行历史 */
    @GetMapping("/runs")
    public List<PipelineRunDTO> runs(
            @RequestParam(value = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pipeline.runs(from, to);
    }

    /** 删除指定日期的流水线执行数据（板块+龙头候选+候选+执行记录） */
    @DeleteMapping("/runs/{snapDate}")
    public Map<String, Object> deleteRun(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate snapDate) {
        pipeline.deleteRun(snapDate);
        return Map.of("message", "已删除 " + snapDate + " 的执行数据", "snapDate", snapDate.toString());
    }
}
