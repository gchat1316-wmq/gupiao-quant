package com.quant.service;

import com.quant.dto.stats.DailyOverviewDTO;
import com.quant.entity.PageViewStat;
import com.quant.entity.UserDailyStat;
import com.quant.entity.User;
import com.quant.repository.PageViewStatRepository;
import com.quant.repository.UserDailyStatRepository;
import com.quant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private final PageViewStatRepository pageViewStatRepo;
    private final UserDailyStatRepository userDailyStatRepo;
    private final UserRepository userRepository;

    // ── 记录页面访问 ────────────────────────────────────────

    /**
     * 前端每次访问页面时调用。
     * @param userId   当前登录用户 ID，未登录传 null
     * @param pagePath 页面路径，如 /gp/invest.html
     * @param sessionId 前端生成的会话 ID（同一浏览器窗口共享）
     * @param userAgent 浏览器 UA
     */
    @Transactional
    public void recordPageView(Long userId, String pagePath, String sessionId, String userAgent) {
        try {
            LocalDate today = LocalDate.now();
            int prevDuration = 0;

            // 找出该用户在当前会话中的上一个页面记录，计算停留时长
            if (sessionId != null && !sessionId.isBlank()) {
                var prevOpt = pageViewStatRepo
                        .findTopBySessionIdAndVisitDateOrderByVisitTimeDesc(sessionId, today);
                if (prevOpt.isPresent()) {
                    PageViewStat prev = prevOpt.get();
                    long secs = Duration.between(prev.getVisitTime(), LocalDateTime.now()).getSeconds();
                    // 上一个页面的停留时长由本次访问来回填（不计入总时长，避免循环更新）
                    prev.setDurationSeconds((int) Math.min(secs, 3600)); // 上限 1 小时
                    pageViewStatRepo.save(prev);
                    prevDuration = (int) secs;
                }
            }

            // 写入新记录
            PageViewStat record = new PageViewStat(userId, pagePath, sessionId, userAgent);
            pageViewStatRepo.save(record);

            // 更新用户每日聚合
            if (userId != null) {
                updateUserDailyStat(userId, today, prevDuration, pagePath);
            }
        } catch (Exception e) {
            log.warn("记录页面访问失败: {}", e.getMessage());
        }
    }

    private void updateUserDailyStat(Long userId, LocalDate date, int durationSeconds, String pagePath) {
        UserDailyStat stat = userDailyStatRepo
                .findByUserIdAndStatDate(userId, date)
                .orElseGet(() -> {
                    UserDailyStat s = new UserDailyStat(userId, date);
                    s.setFirstVisitTime(LocalDateTime.now());
                    return s;
                });
        stat.addPageView(durationSeconds, pagePath);
        stat.setLastVisitTime(LocalDateTime.now());
        userDailyStatRepo.save(stat);
    }

    /** 记录登录事件 */
    @Transactional
    public void recordLogin(Long userId) {
        if (userId == null) return;
        LocalDate today = LocalDate.now();
        UserDailyStat stat = userDailyStatRepo
                .findByUserIdAndStatDate(userId, today)
                .orElseGet(() -> new UserDailyStat(userId, today));
        stat.incrementLogin();
        stat.setLastVisitTime(LocalDateTime.now());
        userDailyStatRepo.save(stat);
    }

    /** 标记新用户 */
    @Transactional
    public void markNewUser(Long userId) {
        if (userId == null) return;
        LocalDate today = LocalDate.now();
        UserDailyStat stat = userDailyStatRepo
                .findByUserIdAndStatDate(userId, today)
                .orElseGet(() -> new UserDailyStat(userId, today));
        stat.setIsNewUser(true);
        userDailyStatRepo.save(stat);
    }

    // ── 查询 API ────────────────────────────────────────────

    /** 近 N 天每日概况 */
    public List<DailyOverviewDTO> getDailyOverview(int days) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days - 1);
        List<Object[]> rows = userDailyStatRepo.dailyOverview(start, end);

        // 补齐无数据的日期
        Map<LocalDate, DailyOverviewDTO> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            DailyOverviewDTO dto = new DailyOverviewDTO();
            dto.setDate((LocalDate) row[0]);
            dto.setUserCount(row[1] == null ? 0 : ((Number) row[1]).intValue());
            dto.setPageViewCount(row[2] == null ? 0 : ((Number) row[2]).intValue());
            dto.setTotalDurationSec(row[3] == null ? 0 : ((Number) row[3]).intValue());
            dto.setLoginCount(row[4] == null ? 0 : ((Number) row[4]).intValue());
            dto.setNewUserCount(row[5] == null ? 0 : ((Number) row[5]).intValue());
            map.put(dto.getDate(), dto);
        }

        // 合并 PageViewStat 的 UV 数据
        List<Object[]> uvRows = pageViewStatRepo.countByDateRange(start, end);
        for (Object[] row : uvRows) {
            LocalDate d = (LocalDate) row[0];
            int uv = row[2] == null ? 0 : ((Number) row[2]).intValue();
            DailyOverviewDTO dto = map.computeIfAbsent(d, date -> new DailyOverviewDTO());
            dto.setDate(d);
            dto.setUniqueVisitors(uv);
        }

        // 填满所有日期
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            map.computeIfAbsent(d, date -> {
                DailyOverviewDTO dto = new DailyOverviewDTO();
                dto.setDate(date);
                dto.setUserCount(0);
                dto.setPageViewCount(0);
                dto.setTotalDurationSec(0);
                dto.setLoginCount(0);
                dto.setNewUserCount(0);
                dto.setUniqueVisitors(0);
                return dto;
            });
        }

        return new ArrayList<>(map.values());
    }

    /** 指定日期的完整统计（含页面排名 + Top 用户） */
    public StatsPageDTO getStatsPage(LocalDate date) {
        StatsPageDTO dto = new StatsPageDTO();
        dto.setDate(date);

        // 每日概况
        List<Object[]> overviewRows = userDailyStatRepo.dailyOverview(date, date);
        DailyOverviewDTO overview = new DailyOverviewDTO();
        overview.setDate(date);
        if (!overviewRows.isEmpty()) {
            Object[] r = overviewRows.get(0);
            overview.setUserCount(r[1] == null ? 0 : ((Number) r[1]).intValue());
            overview.setPageViewCount(r[2] == null ? 0 : ((Number) r[2]).intValue());
            overview.setTotalDurationSec(r[3] == null ? 0 : ((Number) r[3]).intValue());
            overview.setLoginCount(r[4] == null ? 0 : ((Number) r[4]).intValue());
            overview.setNewUserCount(r[5] == null ? 0 : ((Number) r[5]).intValue());
        }
        // UV
        List<Object[]> uvRows = pageViewStatRepo.countByDateRange(date, date);
        int uv = uvRows.isEmpty() ? 0 : (uvRows.get(0)[2] == null ? 0 : ((Number) uvRows.get(0)[2]).intValue());
        overview.setUniqueVisitors(uv);
        dto.setOverview(overview);

        // 页面排名
        List<Object[]> pageRows = pageViewStatRepo.countByDateGroupByPage(date);
        List<PageStatDTO> pageStats = pageRows.stream()
                .map(r -> new PageStatDTO((String) r[0], (Number) r[1], (Number) r[2]))
                .collect(Collectors.toList());
        dto.setPageStats(pageStats);

        // Top 用户
        List<UserDailyStat> topUsers = userDailyStatRepo.topUsersByDate(date);
        List<UserStatDTO> userStats = topUsers.stream().limit(20).map(us -> {
            UserStatDTO usd = new UserStatDTO();
            usd.setUserId(us.getUserId());
            usd.setPageViewCount(us.getPageViewCount() == null ? 0 : us.getPageViewCount());
            usd.setTotalDurationSec(us.getTotalDurationSeconds() == null ? 0 : us.getTotalDurationSeconds());
            usd.setAvgDuration(formatDuration(usd.getTotalDurationSec(), usd.getPageViewCount()));
            userRepository.findById(us.getUserId()).ifPresent(u -> {
                usd.setUsername(u.getUsername());
                usd.setPhone(u.getPhone());
            });
            return usd;
        }).collect(Collectors.toList());
        dto.setTopUsers(userStats);

        return dto;
    }

    /** 页面访问趋势（近 N 天单个页面） */
    public List<Object[]> getPageTrend(String pagePath, int days) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days - 1);
        return pageViewStatRepo.pageTrend(pagePath, start, end);
    }

    private String formatDuration(int totalSec, int pvCount) {
        if (totalSec <= 0 || pvCount <= 0) return "—";
        int avg = totalSec / pvCount;
        if (avg < 60) return avg + "秒";
        if (avg < 3600) return (avg / 60) + "分" + (avg % 60 > 0 ? (avg % 60) + "秒" : "");
        return (avg / 3600) + "时" + ((avg % 3600) / 60) + "分";
    }

    // ── 内部 DTO（避免新建文件） ────────────────────────────

    @lombok.Data
    public static class PageStatDTO {
        private String pagePath;
        private String pageName;
        private int pv;
        private int uv;
        public PageStatDTO(String pagePath, Number pv, Number uv) {
            this.pagePath = pagePath;
            this.pageName = pageName(pagePath);
            this.pv = pv.intValue();
            this.uv = uv == null ? 0 : uv.intValue();
        }
        private static String pageName(String path) {
            if (path == null) return "未知";
            return switch (path) {
                case "/gp/index.html" -> "🏠 首页";
                case "/gp/invest.html" -> "📈 龙江投资";
                case "/gp/xiebo-invest.html" -> "💰 谢博投资";
                case "/gp/prosperity-strong.html" -> "🌿 热点选股";
                case "/gp/prosperity-pick.html" -> "🔬 个股分析";
                case "/gp/tech-ai.html" -> "🤖 科技 AI 行情";
                case "/gp/stock-analysis.html" -> "📊 个股研报";
                case "/gp/market-recap.html" -> "📋 每日复盘";
                case "/gp/study.html" -> "📚 学习搭子";
                case "/gp/potential.html" -> "🎯 潜力选股";
                case "/gp/wish-pool.html" -> "🌟 许愿池";
                case "/gp/admin-users.html" -> "⚙️ 管理后台";
                case "/gp/profile.html" -> "👤 个人中心";
                default -> path.replace("/gp/", "").replace(".html", "");
            };
        }
    }

    @lombok.Data
    public static class UserStatDTO {
        private Long userId;
        private String username;
        private String phone;
        private int pageViewCount;
        private int totalDurationSec;
        private String avgDuration;
    }

    @lombok.Data
    public static class StatsPageDTO {
        private LocalDate date;
        private DailyOverviewDTO overview;
        private List<PageStatDTO> pageStats;
        private List<UserStatDTO> topUsers;
    }
}
