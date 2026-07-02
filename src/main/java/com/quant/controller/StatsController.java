package com.quant.controller;

import com.quant.dto.stats.DailyOverviewDTO;
import com.quant.service.StatsService;
import com.quant.service.StatsService.PageStatDTO;
import com.quant.service.StatsService.StatsPageDTO;
import com.quant.service.StatsService.UserStatDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    // ── 前端上报（无需认证） ────────────────────────────────

    /**
     * 前端每次访问页面时调用，静默处理。
     * POST /api/stats/page-view
     * Body: { pagePath, sessionId }  （可选: userId, userAgent）
     */
    @PostMapping("/page-view")
    public ResponseEntity<?> recordPageView(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "User-Agent", required = false) String ua,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        String pagePath = body.getOrDefault("pagePath", "");
        String sessionId = body.getOrDefault("sessionId", "");
        Long userId = null;
        try {
            if (userIdHeader != null && !userIdHeader.isBlank()) {
                userId = Long.parseLong(userIdHeader);
            }
        } catch (NumberFormatException ignored) {}
        statsService.recordPageView(userId, pagePath, sessionId, ua);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── 管理后台查询（需 ADMIN） ──────────────────────────

    /**
     * 近 N 天每日概况（默认 30 天）
     * GET /api/stats/daily-overview?days=30
     */
    @GetMapping("/daily-overview")
    public List<DailyOverviewDTO> dailyOverview(
            @RequestParam(defaultValue = "30") int days) {
        return statsService.getDailyOverview(Math.min(days, 90));
    }

    /**
     * 指定日期完整统计
     * GET /api/stats/daily?date=2026-06-28
     */
    @GetMapping("/daily")
    public StatsPageDTO dailyStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return statsService.getStatsPage(date != null ? date : LocalDate.now());
    }

    /**
     * 单个页面近 N 天访问趋势
     * GET /api/stats/page-trend?pagePath=/gp/invest.html&days=14
     */
    @GetMapping("/page-trend")
    public List<?> pageTrend(
            @RequestParam String pagePath,
            @RequestParam(defaultValue = "14") int days) {
        List<Object[]> raw = statsService.getPageTrend(pagePath, Math.min(days, 90));
        return raw.stream().map(r -> Map.of(
                "date", r[0].toString(),
                "pv", ((Number) r[1]).intValue()
        )).toList();
    }
}
